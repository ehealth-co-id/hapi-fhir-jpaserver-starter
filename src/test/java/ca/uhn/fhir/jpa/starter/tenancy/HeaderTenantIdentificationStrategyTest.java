package ca.uhn.fhir.jpa.starter.tenancy;

import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HeaderTenantIdentificationStrategyTest {

	private RequestDetails requestWith(String... values) {
		RequestDetails rd = mock(RequestDetails.class);
		when(rd.getHeaders("X-TENANT")).thenReturn(values.length == 0 ? null : List.of(values));
		return rd;
	}

	@Test
	void defaultValueMapsToDefaultTenant() {
		RequestDetails rd = requestWith("ROOT");
		new HeaderTenantIdentificationStrategy("X-TENANT", "ROOT").extractTenant(null, rd);
		verify(rd).setTenantId("DEFAULT");
	}

	@Test
	void ordinaryTenantIsPassedThroughUnchanged() {
		RequestDetails rd = requestWith("Tenant_A-1");
		new HeaderTenantIdentificationStrategy("X-TENANT", "DEFAULT").extractTenant(null, rd);
		verify(rd).setTenantId("Tenant_A-1");
	}

	@Test
	void literalDefaultIsReservedWhenDefaultValueIsDifferent() {
		RequestDetails rd = requestWith("DEFAULT");
		var strategy = new HeaderTenantIdentificationStrategy("X-TENANT", "ROOT");
		assertThrows(InvalidRequestException.class, () -> strategy.extractTenant(null, rd));
	}

	@Test
	void duplicateAndMalformedValuesAreRejected() {
		var strategy = new HeaderTenantIdentificationStrategy("X-TENANT", "DEFAULT");
		assertThrows(InvalidRequestException.class, () -> strategy.extractTenant(null, requestWith("A", "B")));
		assertThrows(InvalidRequestException.class, () -> strategy.extractTenant(null, requestWith("a b")));
		assertThrows(InvalidRequestException.class, () -> strategy.extractTenant(null, requestWith("A".repeat(65))));
	}

	@Test
	void blankPatternDisablesValidation() {
		RequestDetails rd = requestWith("any thing/goes");
		new HeaderTenantIdentificationStrategy("X-TENANT", "DEFAULT", "").extractTenant(null, rd);
		verify(rd).setTenantId("any thing/goes");
		assertEquals("X-TENANT", new HeaderTenantIdentificationStrategy("X-TENANT", "DEFAULT").headerKey);
	}

	private static final String LONG_SECRET = "AGPe3CtNLSNX1eunNxIRyTLkDCaVCTr3wSximVlkxPK0t8fITktvOpKq2lWrv3sku0ctt5qnEWm5zkhmaeVr0r";
	private static final String LONG_SECRET_SHA256 = "7a3871e9a5bb4aea71e94a6aa5b6dad56f1436c2b7f956bf69f5b72e4c294be5";

	@Test
	void defaultValueIsExemptFromThePatternWhateverItsLengthOrCharacters() {
		for (String secret : java.util.List.of(LONG_SECRET, "a.b+c/d=", "x".repeat(300))) {
			RequestDetails rd = requestWith(secret);
			new HeaderTenantIdentificationStrategy("X-TENANT", secret).extractTenant(null, rd);
			verify(rd).setTenantId("DEFAULT");
		}
	}

	@Test
	void duplicateHeaderCarryingTheDefaultValueIsStillRejected() {
		var strategy = new HeaderTenantIdentificationStrategy("X-TENANT", LONG_SECRET);
		assertThrows(InvalidRequestException.class,
			() -> strategy.extractTenant(null, requestWith(LONG_SECRET, LONG_SECRET)));
	}

	@Test
	void sha256DigestSelectsTheDefaultTenant() {
		RequestDetails rd = requestWith(LONG_SECRET);
		new HeaderTenantIdentificationStrategy("X-TENANT", "DEFAULT", "^[A-Za-z0-9_-]{1,64}$", LONG_SECRET_SHA256.toUpperCase())
			.extractTenant(null, rd);
		verify(rd).setTenantId("DEFAULT");
	}

	@Test
	void whenDigestIsConfiguredThePlainDefaultValueNoLongerWorks() {
		var strategy = new HeaderTenantIdentificationStrategy("X-TENANT", "DEFAULT", "^[A-Za-z0-9_-]{1,64}$", LONG_SECRET_SHA256);
		// the literal DEFAULT (the property default) must not become the admin tenant, and is reserved
		assertThrows(InvalidRequestException.class, () -> strategy.extractTenant(null, requestWith("DEFAULT")));
		// a wrong secret is not a tenant either (fails the pattern because of its length)
		assertThrows(InvalidRequestException.class, () -> strategy.extractTenant(null, requestWith(LONG_SECRET + "x")));
	}

	@Test
	void malformedDigestFailsFast() {
		assertThrows(IllegalArgumentException.class,
			() -> new HeaderTenantIdentificationStrategy("X-TENANT", "DEFAULT", null, "not-hex"));
	}
}
