/*
 * Copyright (c) 2020, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package wso2is.key.manager.operations.endpoint.dcr.service;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.context.CarbonContext;
import org.wso2.carbon.context.PrivilegedCarbonContext;
import org.wso2.carbon.identity.application.common.IdentityApplicationManagementException;
import org.wso2.carbon.identity.application.common.model.InboundAuthenticationConfig;
import org.wso2.carbon.identity.application.common.model.InboundAuthenticationRequestConfig;
import org.wso2.carbon.identity.application.common.model.ServiceProvider;
import org.wso2.carbon.identity.application.common.model.User;
import org.wso2.carbon.identity.application.mgt.ApplicationManagementService;
import org.wso2.carbon.identity.application.mgt.ApplicationMgtUtil;
import org.wso2.carbon.identity.oauth.IdentityOAuthAdminException;
import org.wso2.carbon.identity.oauth.OAuthAdminService;
import org.wso2.carbon.identity.oauth.common.OAuthConstants;
import org.wso2.carbon.identity.oauth.common.exception.InvalidOAuthClientException;
import org.wso2.carbon.identity.oauth.config.OAuthServerConfiguration;
import org.wso2.carbon.identity.oauth.dcr.service.DCRMService;
import org.wso2.carbon.identity.oauth.dcr.DCRMConstants;
import org.wso2.carbon.identity.oauth.dcr.DCRMConstants.ErrorMessages;
import org.wso2.carbon.identity.oauth.dcr.util.DCRConstants;
import org.wso2.carbon.identity.oauth.dcr.exception.DCRMException;
import org.wso2.carbon.identity.oauth.dcr.exception.DCRMServerException;
import org.wso2.carbon.identity.oauth.dcr.util.DCRMUtils;
import org.wso2.carbon.identity.oauth.dcr.util.ErrorCodes;
import org.wso2.carbon.identity.oauth.dto.OAuthConsumerAppDTO;
import wso2is.key.manager.operations.endpoint.dcr.bean.ExtendedApplication;
import wso2is.key.manager.operations.endpoint.dcr.bean.ExtendedApplicationRegistrationRequest;
import wso2is.key.manager.operations.endpoint.dcr.bean.ExtendedApplicationUpdateRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Extended DCRMService service is used to manage OAuth2 application registration.
 */
public class ExtendedDCRMService extends DCRMService {

    private static final Log log = LogFactory.getLog(ExtendedDCRMService.class);
    private static OAuthAdminService oAuthAdminService = new OAuthAdminService();

    private static final String AUTH_TYPE_OAUTH_2 = "oauth2";
    private static final String OAUTH_VERSION = "OAuth-2.0";
    private static final String GRANT_TYPE_SEPARATOR = " ";
    private static Pattern clientIdRegexPattern = null;


    /**
     * Create OAuth2/OIDC application.
     *
     * @param registrationRequest
     * @return
     * @throws DCRMException
     */
    public ExtendedApplication registerApplication(ExtendedApplicationRegistrationRequest registrationRequest)
            throws DCRMException {

        return createOAuthApplication(registrationRequest);
    }


    /**
     * Update OAuth/OIDC application.
     *
     * @param updateRequest
     * @param clientId
     * @return
     * @throws DCRMException
     */
    public ExtendedApplication updateApplication(ExtendedApplicationUpdateRequest updateRequest, String clientId) throws
            DCRMException {

        OAuthConsumerAppDTO appDTO = getApplicationDetailsById(clientId);
        String tenantDomain = PrivilegedCarbonContext.getThreadLocalCarbonContext().getTenantDomain();
        String applicationOwner = updateRequest.getApplicationOwner();
        String clientName = updateRequest.getClientName();

        // Update Service Provider
        ServiceProvider sp = getServiceProvider(appDTO.getApplicationName(), tenantDomain);
        if (StringUtils.isNotEmpty(clientName)) {
            // Regex validation of the application name.
            if (!DCRMUtils.isRegexValidated(clientName)) {
                throw DCRMUtils.generateClientException(ErrorMessages.BAD_REQUEST_INVALID_SP_NAME,
                        DCRMUtils.getSPValidatorRegex());
            }
            sp.setApplicationName(clientName);
            updateServiceProvider(sp, tenantDomain, applicationOwner);
        }

        // Update application
        try {
            if (StringUtils.isNotEmpty(clientName)) {
                // Regex validation of the application name.
                if (!DCRMUtils.isRegexValidated(clientName)) {
                    throw DCRMUtils.generateClientException(ErrorMessages.BAD_REQUEST_INVALID_SP_NAME,
                            DCRMUtils.getSPValidatorRegex());
                }
                appDTO.setApplicationName(clientName);
            }
            if (!updateRequest.getGrantTypes().isEmpty()) {
                String grantType = StringUtils.join(updateRequest.getGrantTypes(), GRANT_TYPE_SEPARATOR);
                appDTO.setGrantTypes(grantType);
            }
            if (!updateRequest.getRedirectUris().isEmpty()) {
                String callbackUrl =
                        validateAndSetCallbackURIs(updateRequest.getRedirectUris(), updateRequest.getGrantTypes());
                appDTO.setCallbackUrl(callbackUrl);
            }
            if (updateRequest.getTokenType() != null) {
                appDTO.setTokenType(updateRequest.getTokenType());
            }
            if (StringUtils.isNotEmpty(updateRequest.getBackchannelLogoutUri())) {
                String backChannelLogoutUri = validateBackchannelLogoutURI(updateRequest.getBackchannelLogoutUri());
                appDTO.setBackChannelLogoutUrl(backChannelLogoutUri);
            }
            oAuthAdminService.updateConsumerApplication(appDTO);
        } catch (IdentityOAuthAdminException e) {
            throw DCRMUtils.generateServerException(
                    ErrorMessages.FAILED_TO_UPDATE_APPLICATION, clientId, e);
        }

        return buildResponse(getApplicationDetailsById(clientId));
    }

    private OAuthConsumerAppDTO getApplicationDetailsById(String clientId) throws DCRMException {

        return getApplicationById(clientId, true);
    }

    /**
     * get Application By Id
     * @param clientId
     * @param isApplicationRolePermissionRequired
     * @return OAuthConsumerAppDTO
     * @throws DCRMException
     */
    private OAuthConsumerAppDTO getApplicationById(String clientId, boolean isApplicationRolePermissionRequired)
            throws DCRMException {

        if (StringUtils.isEmpty(clientId)) {
            String errorMessage = "Invalid client_id";
            throw DCRMUtils.generateClientException(
                    ErrorMessages.BAD_REQUEST_INVALID_INPUT, errorMessage);
        }

        try {
            OAuthConsumerAppDTO dto = oAuthAdminService.getOAuthApplicationData(clientId);
            if (dto == null || StringUtils.isEmpty(dto.getApplicationName())) {
                throw DCRMUtils.generateClientException(
                        ErrorMessages.NOT_FOUND_APPLICATION_WITH_ID, clientId);
            } else if (isApplicationRolePermissionRequired && !isUserAuthorized(clientId)) {
                throw DCRMUtils.generateClientException(
                        ErrorMessages.FORBIDDEN_UNAUTHORIZED_USER, clientId);
            }
            return dto;
        } catch (IdentityOAuthAdminException e) {
            if (e.getCause() instanceof InvalidOAuthClientException) {
                throw DCRMUtils
                        .generateClientException(ErrorMessages.NOT_FOUND_APPLICATION_WITH_ID, clientId);
            }
            throw DCRMUtils.generateServerException(
                    ErrorMessages.FAILED_TO_GET_APPLICATION_BY_ID, clientId, e);
        }
    }

    /**
     * create OAuthApplication
     * @param registrationRequest
     * @return ExtendedApplication
     * @throws DCRMException
     */
    private ExtendedApplication createOAuthApplication(ExtendedApplicationRegistrationRequest registrationRequest)
            throws DCRMException {

        String applicationOwner = registrationRequest.getApplicationOwner();
        String tenantDomain = PrivilegedCarbonContext.getThreadLocalCarbonContext().getTenantDomain();
        PrivilegedCarbonContext.getThreadLocalCarbonContext().setUsername(applicationOwner);

        String spName = registrationRequest.getClientName();
        String templateName = registrationRequest.getSpTemplateName();


        // Regex validation of the application name.
        if (!DCRMUtils.isRegexValidated(spName)) {
            throw DCRMUtils.generateClientException(ErrorMessages.BAD_REQUEST_INVALID_SP_NAME,
                    DCRMUtils.getSPValidatorRegex());
        }

        // Check whether a service provider already exists for the name we are trying to register the OAuth app with.
        if (isServiceProviderExist(spName, tenantDomain)) {
            throw DCRMUtils.generateClientException(ErrorMessages.CONFLICT_EXISTING_APPLICATION, spName);
        }

        if (StringUtils.isNotEmpty(registrationRequest.getConsumerKey()) && isClientIdExist(
                registrationRequest.getConsumerKey())) {
            throw DCRMUtils.generateClientException(ErrorMessages.CONFLICT_EXISTING_CLIENT_ID,
                    registrationRequest.getConsumerKey());
        }

        // Create a service provider.
        ServiceProvider serviceProvider = createServiceProvider(applicationOwner, tenantDomain, spName, templateName);

        OAuthConsumerAppDTO createdApp;
        try {
            // Register the OAuth app.
            createdApp = createOAuthApp(registrationRequest, applicationOwner, tenantDomain, spName);
        } catch (DCRMException ex) {
            if (log.isDebugEnabled()) {
                log.debug("OAuth app: " + spName + " registration failed in tenantDomain: " + tenantDomain + ". " +
                        "Deleting the service provider: " + spName + " to rollback.");
            }
            deleteServiceProvider(spName, tenantDomain, applicationOwner);
            throw ex;
        }

        try {
            updateServiceProviderWithOAuthAppDetails(serviceProvider, createdApp, applicationOwner, tenantDomain);
        } catch (DCRMException ex) {
            // Delete the OAuth app created. This will also remove the registered SP for the OAuth app.
            deleteApplication(createdApp.getOauthConsumerKey());
            throw ex;
        }
        return buildResponse(createdApp);
    }

    /**
     * Build the response
     * @param createdApp
     * @return ExtendedApplication
     */
    private ExtendedApplication buildResponse(OAuthConsumerAppDTO createdApp) {

        List<String> redirectUrisList = new ArrayList<>();
        redirectUrisList.add(createdApp.getCallbackUrl());

        List<String> grantTypeList = new ArrayList<>();
        grantTypeList.add(createdApp.getGrantTypes());

        ExtendedApplication application = new ExtendedApplication();
        application.setClientName(createdApp.getApplicationName());
        application.setClientId(createdApp.getOauthConsumerKey());
        application.setClientSecret(createdApp.getOauthConsumerSecret());
        application.setRedirectUris(redirectUrisList);
        application.setGrantTypes(grantTypeList);
        return application;
    }

    /**
     * update ServiceProvider With OAuthAppDetails
     *
     * @param serviceProvider
     * @param createdApp
     * @param applicationOwner
     * @param tenantDomain
     * @throws DCRMException
     */
    private void updateServiceProviderWithOAuthAppDetails(ServiceProvider serviceProvider,
                                                          OAuthConsumerAppDTO createdApp,
                                                          String applicationOwner,
                                                          String tenantDomain) throws DCRMException {
        // Update created service provider, InboundAuthenticationConfig with OAuth application info.
        InboundAuthenticationConfig inboundAuthenticationConfig = new InboundAuthenticationConfig();
        List<InboundAuthenticationRequestConfig> inboundAuthenticationRequestConfigs = new ArrayList<>();

        InboundAuthenticationRequestConfig inboundAuthenticationRequestConfig =
                new InboundAuthenticationRequestConfig();
        inboundAuthenticationRequestConfig.setInboundAuthKey(createdApp.getOauthConsumerKey());
        inboundAuthenticationRequestConfig.setInboundAuthType(AUTH_TYPE_OAUTH_2);
        inboundAuthenticationRequestConfigs.add(inboundAuthenticationRequestConfig);
        inboundAuthenticationConfig.setInboundAuthenticationRequestConfigs(inboundAuthenticationRequestConfigs
                .toArray(new InboundAuthenticationRequestConfig[inboundAuthenticationRequestConfigs
                        .size()]));
        serviceProvider.setInboundAuthenticationConfig(inboundAuthenticationConfig);
        //Set SaaS app option
        serviceProvider.setSaasApp(false);

        // Update the Service Provider app to add OAuthApp as an Inbound Authentication Config
        updateServiceProvider(serviceProvider, tenantDomain, applicationOwner);
    }

    private OAuthConsumerAppDTO createOAuthApp(ExtendedApplicationRegistrationRequest registrationRequest,
                                               String applicationOwner,
                                               String tenantDomain,
                                               String spName) throws DCRMException {
        // Then Create OAuthApp
        OAuthConsumerAppDTO oAuthConsumerApp = new OAuthConsumerAppDTO();
        oAuthConsumerApp.setApplicationName(spName);
        oAuthConsumerApp.setUsername(applicationOwner);
        oAuthConsumerApp.setCallbackUrl(
                validateAndSetCallbackURIs(registrationRequest.getRedirectUris(), registrationRequest.getGrantTypes()));
        String grantType = StringUtils.join(registrationRequest.getGrantTypes(), GRANT_TYPE_SEPARATOR);
        oAuthConsumerApp.setGrantTypes(grantType);
        oAuthConsumerApp.setOAuthVersion(OAUTH_VERSION);
        oAuthConsumerApp.setTokenType(registrationRequest.getTokenType());
        oAuthConsumerApp.setBackChannelLogoutUrl(
                validateBackchannelLogoutURI(registrationRequest.getBackchannelLogoutUri()));

        if (StringUtils.isNotEmpty(registrationRequest.getConsumerKey())) {
            String clientIdRegex = OAuthServerConfiguration.getInstance().getClientIdValidationRegex();
            if (clientIdMatchesRegex(registrationRequest.getConsumerKey(), clientIdRegex)) {
                oAuthConsumerApp.setOauthConsumerKey(registrationRequest.getConsumerKey());
            } else {
                throw DCRMUtils
                        .generateClientException(ErrorMessages.BAD_REQUEST_CLIENT_ID_VIOLATES_PATTERN,
                                clientIdRegex);
            }
        }

        if (StringUtils.isNotEmpty(registrationRequest.getConsumerSecret())) {
            oAuthConsumerApp.setOauthConsumerSecret(registrationRequest.getConsumerSecret());
        }
        if (log.isDebugEnabled()) {
            log.debug("Creating OAuth Application: " + spName + " in tenant: " + tenantDomain);
        }

        OAuthConsumerAppDTO createdApp;

        try {
            createdApp = oAuthAdminService.registerAndRetrieveOAuthApplicationData(oAuthConsumerApp);
        } catch (IdentityOAuthAdminException e) {
            throw DCRMUtils.generateServerException(
                    ErrorMessages.FAILED_TO_REGISTER_APPLICATION, spName, e);
        }

        if (log.isDebugEnabled()) {
            log.debug("Created OAuth Application: " + spName + " in tenant: " + tenantDomain);
        }

        if (createdApp == null) {
            throw DCRMUtils.generateServerException(ErrorMessages.FAILED_TO_REGISTER_APPLICATION, spName);
        }
        return createdApp;
    }

    private ServiceProvider createServiceProvider(String applicationOwner, String tenantDomain,
                                                  String spName, String templateName) throws DCRMException {
        // Create the Service Provider
        ServiceProvider sp = new ServiceProvider();
        sp.setApplicationName(spName);
        User user = new User();
        user.setUserName(applicationOwner);
        user.setTenantDomain(tenantDomain);
        sp.setOwner(user);
        sp.setDescription("Service Provider for application " + spName);

        createServiceProvider(sp, tenantDomain, applicationOwner, templateName);

        // Get created service provider.
        ServiceProvider clientSP = getServiceProvider(spName, tenantDomain);
        if (clientSP == null) {
            throw DCRMUtils.generateClientException(ErrorMessages.FAILED_TO_REGISTER_SP, spName);
        }
        return clientSP;
    }

    /**
     * Check whether servers provider exist with a given name in the tenant.
     *
     * @param serviceProviderName
     * @param tenantDomain
     * @return
     */
    private boolean isServiceProviderExist(String serviceProviderName, String tenantDomain) {

        ServiceProvider serviceProvider = null;
        try {
            serviceProvider = getServiceProvider(serviceProviderName, tenantDomain);
        } catch (DCRMException e) {
            log.error(
                    "Error while retrieving service provider: " + serviceProviderName + " in tenant: " + tenantDomain);
        }

        return serviceProvider != null;
    }

    /**
     * Check whether the provided client id is exists.
     *
     * @param clientId client id.
     * @return true if application exists with the client id.
     * @throws DCRMException in case of failure.
     */
    private boolean isClientIdExist(String clientId) throws DCRMException {

        try {
            OAuthConsumerAppDTO dto = oAuthAdminService.getOAuthApplicationData(clientId);
            return dto != null && StringUtils.isNotBlank(dto.getApplicationName());
        } catch (IdentityOAuthAdminException e) {
            if (e.getCause() instanceof InvalidOAuthClientException) {
                return false;
            }
            throw DCRMUtils
                    .generateServerException(ErrorMessages.FAILED_TO_GET_APPLICATION_BY_ID, clientId, e);
        }
    }

    /**
     * Get ServiceProvider
     *
     * @param applicationName
     * @param tenantDomain
     * @return ServiceProvider
     * @throws DCRMException
     */
    private ServiceProvider getServiceProvider(String applicationName, String tenantDomain) throws DCRMException {

        ServiceProvider serviceProvider = null;
        try {
            ApplicationManagementService appMgtService = ApplicationManagementService.getInstance();
            serviceProvider = appMgtService.getServiceProvider(applicationName, tenantDomain);
        } catch (IdentityApplicationManagementException e) {
            throw DCRMUtils.generateServerException(
                    ErrorMessages.FAILED_TO_GET_SP, applicationName, e);
        }
        return serviceProvider;
    }

    /**
     * update Service Provider
     *
     * @param serviceProvider
     * @param tenantDomain
     * @param userName
     * @throws DCRMException
     */
    private void updateServiceProvider(ServiceProvider serviceProvider, String tenantDomain, String userName)
            throws DCRMException {

        try {
            ApplicationManagementService appMgtService = ApplicationManagementService.getInstance();
            appMgtService.updateApplication(serviceProvider, tenantDomain, userName);
        } catch (IdentityApplicationManagementException e) {
            throw DCRMUtils.generateServerException(
                    ErrorMessages.FAILED_TO_UPDATE_SP, serviceProvider.getApplicationName(), e);
        }
    }

    /**
     * create ServiceProvider
     *
     * @param serviceProvider
     * @param tenantDomain
     * @param username
     * @param templateName
     * @throws DCRMException
     */
    private void createServiceProvider(ServiceProvider serviceProvider, String tenantDomain, String username,
                                       String templateName) throws DCRMException {

        try {
            ApplicationManagementService appMgtService = ApplicationManagementService.getInstance();
            if (templateName != null) {
                boolean isTemplateExists = appMgtService.isExistingApplicationTemplate(templateName, tenantDomain);
                if (!isTemplateExists) {
                    throw DCRMUtils.generateClientException(ErrorMessages
                            .BAD_REQUEST_INVALID_SP_TEMPLATE_NAME, templateName);
                }
            }
            appMgtService.createApplicationWithTemplate(serviceProvider, tenantDomain, username, templateName);
        } catch (IdentityApplicationManagementException e) {
            String errorMessage =
                    "Error while creating service provider: " + serviceProvider.getApplicationName() +
                            " in tenant: " + tenantDomain;
            throw new DCRMException(ErrorCodes.BAD_REQUEST.toString(), errorMessage, e);
        }
    }

    /**
     * Delete service provider
     *
     * @param applicationName
     * @param tenantDomain
     * @param userName
     * @throws DCRMException
     */
    private void deleteServiceProvider(String applicationName,
                                       String tenantDomain, String userName) throws DCRMException {

        try {
            ApplicationManagementService appMgtService = ApplicationManagementService.getInstance();
            appMgtService.deleteApplication(applicationName, tenantDomain, userName);
        } catch (IdentityApplicationManagementException e) {
            throw DCRMUtils
                    .generateServerException(ErrorMessages.FAILED_TO_DELETE_SP, applicationName, e);
        }
    }

    /**
     * validate and set CallbackURIs
     *
     * @param redirectUris
     * @param grantTypes
     * @return CallbackURIs
     * @throws DCRMException
     */
    private String validateAndSetCallbackURIs(List<String> redirectUris, List<String> grantTypes) throws DCRMException {

        //TODO: After implement multi-urls to the oAuth application, we have to change this API call
        //TODO: need to validate before processing request
        if (redirectUris.size() == 0) {
            if (isRedirectURIMandatory(grantTypes)) {
                String errorMessage = "RedirectUris property must have at least one URI value when using " +
                        "Authorization code or implicit grant types.";
                throw DCRMUtils.generateClientException(
                        ErrorMessages.BAD_REQUEST_INVALID_INPUT, errorMessage);
            } else {
                return StringUtils.EMPTY;
            }
        } else if (redirectUris.size() == 1) {
            String redirectUri = redirectUris.get(0);
            if (DCRMUtils.isRedirectionUriValid(redirectUri)) {
                return redirectUri;
            } else {
                throw DCRMUtils.generateClientException(
                        ErrorMessages.BAD_REQUEST_INVALID_REDIRECT_URI, redirectUri);
            }

        } else {
            return OAuthConstants.CALLBACK_URL_REGEXP_PREFIX + createRegexPattern(redirectUris);
        }
    }

    /**
     * validate Backchannel LogoutURI
     *
     * @param backchannelLogoutUri
     * @return validation of Backchannel LogoutURI
     * @throws DCRMException
     */
    private String validateBackchannelLogoutURI(String backchannelLogoutUri) throws DCRMException {

        if (DCRMUtils.isBackchannelLogoutUriValid(backchannelLogoutUri)) {
            return backchannelLogoutUri;
        } else {
            throw DCRMUtils.generateClientException(
                    ErrorMessages.BAD_REQUEST_INVALID_BACKCHANNEL_LOGOUT_URI, backchannelLogoutUri);
        }
    }

    /**
     * Check RedirectURI is Mandatory
     *
     * @param grantTypes
     * @return isRedirectURIMandatory
     */
    private boolean isRedirectURIMandatory(List<String> grantTypes) {

        return grantTypes.contains(DCRConstants.GrantTypes.AUTHORIZATION_CODE) ||
                grantTypes.contains(DCRConstants.GrantTypes.IMPLICIT);
    }

    /**
     * create Regex Pattern
     *
     * @param redirectURIs
     * @return regex pattern
     * @throws DCRMException
     */
    private String createRegexPattern(List<String> redirectURIs) throws DCRMException {

        StringBuilder regexPattern = new StringBuilder();
        for (String redirectURI : redirectURIs) {
            if (DCRMUtils.isRedirectionUriValid(redirectURI)) {
                if (regexPattern.length() > 0) {
                    regexPattern.append("|").append(redirectURI);
                } else {
                    regexPattern.append("(").append(redirectURI);
                }
            } else {
                throw DCRMUtils.generateClientException(
                        ErrorMessages.BAD_REQUEST_INVALID_REDIRECT_URI, redirectURI);
            }
        }
        if (regexPattern.length() > 0) {
            regexPattern.append(")");
        }
        return regexPattern.toString();
    }

    /**
     * Validate the user
     *
     * @param clientId
     * @return user authorized or not
     * @throws DCRMServerException
     */
    private boolean isUserAuthorized(String clientId) throws DCRMServerException {

        try {
            ApplicationManagementService appMgtService = ApplicationManagementService.getInstance();
            String tenantDomain = PrivilegedCarbonContext.getThreadLocalCarbonContext().getTenantDomain();
            String spName = appMgtService.
                    getServiceProviderNameByClientId(clientId, DCRMConstants.OAUTH2, tenantDomain);
            String threadLocalUserName = CarbonContext.getThreadLocalCarbonContext().getUsername();
            return ApplicationMgtUtil.isUserAuthorized(spName, threadLocalUserName);
        } catch (IdentityApplicationManagementException e) {
            throw DCRMUtils.generateServerException(
                    ErrorMessages.FAILED_TO_GET_APPLICATION_BY_ID, clientId, e);
        }
    }

    /**
     * Validate client id according to the regex.
     *
     * @param clientId
     * @param clientIdValidatorRegex
     * @return validated or not
     */
    private static boolean clientIdMatchesRegex(String clientId, String clientIdValidatorRegex) {

        if (clientIdRegexPattern == null) {
            clientIdRegexPattern = Pattern.compile(clientIdValidatorRegex);
        }
        return clientIdRegexPattern.matcher(clientId).matches();
    }

}
