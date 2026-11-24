package ca.uhn.fhir.jpa.starter.tenancy;

import ca.uhn.fhir.i18n.HapiLocalizer;
import ca.uhn.fhir.i18n.Msg;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.RestfulServer;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import ca.uhn.fhir.rest.server.tenant.ITenantIdentificationStrategy;
import ca.uhn.fhir.util.UrlPathTokenizer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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
  private final byte[] defaultTenantHeaderValueSha256;

  public HeaderTenantIdentificationStrategy(String headerKey, String defaultTenantHeaderValue) {
    this(headerKey, defaultTenantHeaderValue, DEFAULT_TENANT_ID_PATTERN);
  }

  public HeaderTenantIdentificationStrategy(
      String headerKey, String defaultTenantHeaderValue, String tenantIdPattern) {
    this(headerKey, defaultTenantHeaderValue, tenantIdPattern, null);
  }

  /**
   * @param tenantIdPattern regex a tenant ID must match; blank disables the check
   * @param defaultTenantHeaderValueSha256 hex SHA-256 of the header value that selects the DEFAULT partition. When
   *     set it replaces defaultTenantHeaderValue, so the secret itself does not have to be configured
   */
  public HeaderTenantIdentificationStrategy(
      String headerKey,
      String defaultTenantHeaderValue,
      String tenantIdPattern,
      String defaultTenantHeaderValueSha256) {
    this.headerKey = headerKey;
    this.defaultTenantHeaderValue = defaultTenantHeaderValue;
    this.tenantIdPattern = StringUtils.isBlank(tenantIdPattern) ? null : Pattern.compile(tenantIdPattern);
    this.defaultTenantHeaderValueSha256 = parseSha256(defaultTenantHeaderValueSha256);
  }

  private static byte[] parseSha256(String hex) {
    if (StringUtils.isBlank(hex)) {
      return null;
    }
    String h = hex.trim();
    if (!h.matches("^[0-9a-fA-F]{64}$")) {
      throw new IllegalArgumentException("default_tenant_header_value_sha256 must be 64 hex characters");
    }
    byte[] out = new byte[32];
    for (int i = 0; i < 32; i++) {
      out[i] = (byte) Integer.parseInt(h.substring(2 * i, 2 * i + 2), 16);
    }
    return out;
  }

  /** Constant-time check of the header value against the configured default tenant value (or its digest). */
  private boolean isDefaultTenantValue(String value) {
    try {
      MessageDigest sha = MessageDigest.getInstance("SHA-256");
      byte[] actual = sha.digest(value.getBytes(StandardCharsets.UTF_8));
      if (defaultTenantHeaderValueSha256 != null) {
        return MessageDigest.isEqual(actual, defaultTenantHeaderValueSha256);
      }
      return defaultTenantHeaderValue != null
          && MessageDigest.isEqual(actual, sha.digest(defaultTenantHeaderValue.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  public void extractTenant(UrlPathTokenizer theUrlPathTokenizer, RequestDetails theRequestDetails) {
    List<String> tenantIds = theRequestDetails.getHeaders(this.headerKey);
    if (tenantIds != null && !tenantIds.isEmpty()) {
      // more than one value: a gateway and this server could disagree about the tenant
      if (tenantIds.size() > 1) {
        throw invalidHeader();
      }
      String tenantId = tenantIds.get(0);
      if (tenantId == null) {
        throw invalidHeader();
      }
      // the default tenant value is a credential, not a tenant name: it is checked before (and exempt from) the pattern
      if (isDefaultTenantValue(tenantId)) {
        theRequestDetails.setTenantId("DEFAULT");
        return;
      }
      if (tenantIdPattern != null && !tenantIdPattern.matcher(tenantId).matches()) {
        throw invalidHeader();
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