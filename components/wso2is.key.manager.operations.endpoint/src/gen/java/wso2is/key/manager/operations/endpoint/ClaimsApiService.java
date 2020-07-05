package wso2is.key.manager.operations.endpoint;

import wso2is.key.manager.operations.endpoint.*;
import wso2is.key.manager.operations.endpoint.dto.*;

import org.apache.cxf.jaxrs.ext.MessageContext;
import org.apache.cxf.jaxrs.ext.multipart.Attachment;
import org.apache.cxf.jaxrs.ext.multipart.Multipart;


import wso2is.key.manager.operations.endpoint.dto.ClaimListDTO;
import wso2is.key.manager.operations.endpoint.dto.ClaimRequestDTO;
import wso2is.key.manager.operations.endpoint.dto.ErrorDTO;

import java.util.List;

import java.io.InputStream;

import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;


public interface ClaimsApiService {
      public Response claimsGeneratePost(ClaimRequestDTO properties, MessageContext messageContext) ;
      public Response claimsGet(String username, String domain, String dialect, MessageContext messageContext) ;
}
