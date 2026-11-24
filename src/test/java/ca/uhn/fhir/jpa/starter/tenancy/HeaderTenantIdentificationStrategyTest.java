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
}
