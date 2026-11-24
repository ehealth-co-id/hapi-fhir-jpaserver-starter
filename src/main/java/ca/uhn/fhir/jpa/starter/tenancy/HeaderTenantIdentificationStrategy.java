package ca.uhn.fhir.jpa.starter.tenancy;

import ca.uhn.fhir.i18n.HapiLocalizer;
import ca.uhn.fhir.i18n.Msg;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.RestfulServer;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import ca.uhn.fhir.rest.server.tenant.ITenantIdentificationStrategy;
import ca.uhn.fhir.util.UrlPathTokenizer;
import java.util.List;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Validate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HeaderTenantIdentificationStrategy implements ITenantIdentificationStrategy {
  private static final Logger ourLog = LoggerFactory.getLogger(HeaderTenantIdentificationStrategy.class);
  public static final String DEFAULT_TENANT_ID_PATTERN = "^[A-Za-z0-9_-]{1,64}$";
  public String defaultTenantHeaderValue;
  public String headerKey;
  private final Pattern tenantIdPattern;

  public HeaderTenantIdentificationStrategy(String headerKey, String defaultTenantHeaderValue) {
    this(headerKey, defaultTenantHeaderValue, DEFAULT_TENANT_ID_PATTERN);
  }

  /**
   * @param tenantIdPattern regex a tenant ID must match; blank disables the check
   */
  public HeaderTenantIdentificationStrategy(
      String headerKey, String defaultTenantHeaderValue, String tenantIdPattern) {
    this.headerKey = headerKey;
    this.defaultTenantHeaderValue = defaultTenantHeaderValue;
    this.tenantIdPattern = StringUtils.isBlank(tenantIdPattern) ? null : Pattern.compile(tenantIdPattern);
  }

  public void extractTenant(UrlPathTokenizer theUrlPathTokenizer, RequestDetails theRequestDetails) {
    List<String> tenantIds = theRequestDetails.getHeaders(this.headerKey);
    if (tenantIds != null && !tenantIds.isEmpty()) {
      // more than one value: a gateway and this server could disagree about the tenant
      if (tenantIds.size() > 1) {
        throw invalidHeader();
      }
      String tenantId = tenantIds.get(0);
      if (tenantId == null || (tenantIdPattern != null && !tenantIdPattern.matcher(tenantId).matches())) {
        throw invalidHeader();
      }
      if (tenantId.equals(this.defaultTenantHeaderValue)) {
        theRequestDetails.setTenantId("DEFAULT");
        return;
      }
      if (!tenantId.equals("DEFAULT")) {
        theRequestDetails.setTenantId(tenantId);
        return;
      }
      throw invalidHeader();
    }

    HapiLocalizer localizer = theRequestDetails.getServer().getFhirContext().getLocalizer();
    String var10002 = Msg.code(307);
    throw new InvalidRequestException(var10002 + localizer.getMessage(RestfulServer.class, "rootRequest.multitenant", new Object[0]));
  }

  private InvalidRequestException invalidHeader() {
    return new InvalidRequestException("Invalid or ambiguous " + this.headerKey + " header");
  }

  @Override
  public String massageServerBaseUrl(String theFhirServerBase, RequestDetails theRequestDetails) {
    Validate.notNull(theRequestDetails.getTenantId(), "theTenantId is not populated on this request", new Object[0]);
    return theFhirServerBase + "/";
  }

  @Override
  public String resolveRelativeUrl(String theRelativeUrl, RequestDetails theRequestDetails) {
    UrlPathTokenizer tokenizer = new UrlPathTokenizer(theRelativeUrl);
    // there is no more tokens in the URL - skip url resolution
    if (!tokenizer.hasMoreTokens() || tokenizer.peek() == null) {
      return theRelativeUrl;
    }
    String nextToken = tokenizer.peek();
    // there is no tenant ID in parent request details or tenant ID is already present in URL - skip url resolution
    if (theRequestDetails.getTenantId() == null || nextToken.equals(theRequestDetails.getTenantId())) {
      return theRelativeUrl;
    }

    // token is Resource type or operation - adding tenant ID from parent request details
    if (isResourceType(nextToken, theRequestDetails) || isOperation(nextToken)) {
      return theRequestDetails.getTenantId() + "/" + theRelativeUrl;
    } else {
      return theRelativeUrl;
    }
  }

  private boolean isOperation(String theToken) {
    return theToken.startsWith("$");
  }

  private boolean isResourceType(String token, RequestDetails theRequestDetails) {
    return theRequestDetails.getFhirContext().getResourceTypes().stream().anyMatch(type -> type.equals(token));
  }
}