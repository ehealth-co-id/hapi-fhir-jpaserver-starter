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
import org.apache.commons.lang3.Validate;

/**
 * Identifies the tenant from a request header (default {@code X-TENANT}) instead of from the URL path.
 * <p>
 * The header is only a claim by the client. The gateway in front of the server is the trust boundary: it must strip any
 * client supplied value and set the header from the authenticated identity, and the server must not be reachable
 * except through it.
 * <ul>
 *   <li>A missing header is rejected (never falls back to DEFAULT).</li>
 *   <li>More than one header value is rejected, so a gateway and this server cannot disagree about the tenant.</li>
 *   <li>The value must match a pattern (default {@value #DEFAULT_TENANT_ID_PATTERN}); anything else is rejected with a
 *   generic message.</li>
 *   <li>The configured default value selects the {@value #DEFAULT_TENANT}  partition. The literal DEFAULT (in any case)
 *   is otherwise reserved and rejected, so there is exactly one way to address the default partition.</li>
 * </ul>
 */
public class HeaderTenantIdentificationStrategy implements ITenantIdentificationStrategy {

	public static final String DEFAULT_TENANT = "DEFAULT";
	public static final String DEFAULT_TENANT_ID_PATTERN = "^[A-Za-z0-9_-]{1,64}$";

	private final String headerKey;
	private final String defaultTenantHeaderValue;
	private final Pattern tenantIdPattern;

	public HeaderTenantIdentificationStrategy(String headerKey, String defaultTenantHeaderValue) {
		this(headerKey, defaultTenantHeaderValue, DEFAULT_TENANT_ID_PATTERN);
	}

	public HeaderTenantIdentificationStrategy(
			String headerKey, String defaultTenantHeaderValue, String tenantIdPattern) {
		Validate.notBlank(headerKey, "headerKey must not be blank");
		Validate.notBlank(defaultTenantHeaderValue, "defaultTenantHeaderValue must not be blank");
		this.headerKey = headerKey;
		this.defaultTenantHeaderValue = defaultTenantHeaderValue;
		this.tenantIdPattern = Pattern.compile(tenantIdPattern == null ? DEFAULT_TENANT_ID_PATTERN : tenantIdPattern);
	}

	@Override
	public void extractTenant(UrlPathTokenizer theUrlPathTokenizer, RequestDetails theRequestDetails) {
		List<String> values = theRequestDetails.getHeaders(headerKey);

		if (values == null || values.isEmpty()) {
			HapiLocalizer localizer =
					theRequestDetails.getServer().getFhirContext().getLocalizer();
			throw new InvalidRequestException(
					Msg.code(307) + localizer.getMessage(RestfulServer.class, "rootRequest.multitenant"));
		}
		if (values.size() > 1) {
			throw invalid();
		}

		String tenantId = values.get(0);
		if (tenantId == null || !tenantIdPattern.matcher(tenantId).matches()) {
			throw invalid();
		}

		if (tenantId.equals(defaultTenantHeaderValue)) {
			theRequestDetails.setTenantId(DEFAULT_TENANT);
		} else if (tenantId.equalsIgnoreCase(DEFAULT_TENANT)) {
			// reserved: the default partition is addressed only through the configured default value
			throw invalid();
		} else {
			theRequestDetails.setTenantId(tenantId);
		}
	}

	private InvalidRequestException invalid() {
		return new InvalidRequestException("Invalid or ambiguous " + headerKey + " header");
	}

	/** The tenant is not part of the URL, so the server base URL is left as is. */
	@Override
	public String massageServerBaseUrl(String theFhirServerBase, RequestDetails theRequestDetails) {
		Validate.notNull(theRequestDetails.getTenantId(), "theTenantId is not populated on this request");
		return theFhirServerBase;
	}

	/** The tenant is not part of the URL, so relative URLs (e.g. transaction entries) are left as is. */
	@Override
	public String resolveRelativeUrl(String theRelativeUrl, RequestDetails theRequestDetails) {
		return theRelativeUrl;
	}
}
