package ca.uhn.fhir.jpa.starter;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.api.CacheControlDirective;
import ca.uhn.fhir.rest.client.api.IClientInterceptor;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.client.api.IHttpRequest;
import ca.uhn.fhir.rest.client.api.IHttpResponse;
import ca.uhn.fhir.rest.client.api.ServerValidationModeEnum;
import ca.uhn.fhir.rest.client.interceptor.LoggingInterceptor;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import ca.uhn.fhir.rest.server.provider.ProviderConstants;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.CodeType;
import org.hl7.fhir.r4.model.IntegerType;
import org.hl7.fhir.r4.model.Parameters;
import org.hl7.fhir.r4.model.Patient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tenant is selected with the X-TENANT header (see HeaderTenantIdentificationStrategy), not with the URL.
 */
@ExtendWith(SpringExtension.class)
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = {Application.class}, properties =
	{
		"spring.datasource.url=jdbc:h2:mem:dbr4-mt",
		"spring.jpa.properties.hibernate.search.backend.directory.type=local-heap",
		"hapi.fhir.fhir_version=r4",
		"hapi.fhir.subscription.websocket_enabled=true",
		"hapi.fhir.cr_enabled=false",
		"hapi.fhir.partitioning.partitioning_include_in_search_hashes=false",
		"hapi.fhir.partitioning.request_tenant_partitioning_mode=true",
	})
class MultitenantServerR4IT {

	/** Adds the given header values, as given, to every request. */
	private static class HeaderInterceptor implements IClientInterceptor {
		private final List<String> values = new ArrayList<>();

		void set(String... theValues) {
			values.clear();
			values.addAll(List.of(theValues));
		}

		@Override
		public void interceptRequest(IHttpRequest theRequest) {
			values.forEach(v -> theRequest.addHeader("X-TENANT", v));
		}

		@Override
		public void interceptResponse(IHttpResponse theResponse) throws IOException {
			// nothing
		}
	}

	private HeaderInterceptor tenant;
	private IGenericClient ourClient;
	@LocalServerPort
	private int port;

	@BeforeEach
	void beforeEach() {
		tenant = new HeaderInterceptor();
		FhirContext ctx = FhirContext.forR4();
		ctx.getRestfulClientFactory().setServerValidationMode(ServerValidationModeEnum.NEVER);
		ctx.getRestfulClientFactory().setSocketTimeout(1200 * 1000);
		ourClient = ctx.newRestfulGenericClient("http://localhost:" + port + "/fhir/");
		ourClient.registerInterceptor(new LoggingInterceptor(true));
		ourClient.registerInterceptor(tenant);
	}

	private void createPartition(int id, String name) {
		tenant.set("DEFAULT");
		ourClient
			.operation()
			.onServer()
			.named(ProviderConstants.PARTITION_MANAGEMENT_CREATE_PARTITION)
			.withParameter(Parameters.class, ProviderConstants.PARTITION_MANAGEMENT_PARTITION_ID, new IntegerType(id))
			.andParameter(ProviderConstants.PARTITION_MANAGEMENT_PARTITION_NAME, new CodeType(name))
			.execute();
	}

	private IIdType createPatient(String tenantId, String family) {
		tenant.set(tenantId);
		Patient pt = new Patient();
		pt.addName().setFamily(family);
		return ourClient.create().resource(pt).execute().getId();
	}

	private Bundle searchPatients(String tenantId) {
		tenant.set(tenantId);
		return ourClient.search().forResource(Patient.class).returnBundle(Bundle.class)
			.cacheControl(new CacheControlDirective().setNoCache(true)).execute();
	}

	@Test
	void testCreateAndReadInTenantA() {
		createPartition(1, "TENANT-A");
		createPatient("TENANT-A", "Family A");

		Bundle searchResult = searchPatients("TENANT-A");
		assertEquals(1, searchResult.getEntry().size());
		assertEquals("Family A", ((Patient) searchResult.getEntry().get(0).getResource()).getName().get(0).getFamily());
	}

	@Test
	void testTenantsAreIsolated() {
		createPartition(2, "TENANT-B");
		createPartition(3, "TENANT-C");
		IIdType idB = createPatient("TENANT-B", "Family B");
		createPatient("TENANT-C", "Family C");

		// search only sees own tenant
		Bundle b = searchPatients("TENANT-B");
		assertEquals(1, b.getEntry().size());
		assertEquals("Family B", ((Patient) b.getEntry().get(0).getResource()).getName().get(0).getFamily());

		// read of another tenant's resource by id is not found
		tenant.set("TENANT-C");
		assertThrows(ResourceNotFoundException.class,
			() -> ourClient.read().resource(Patient.class).withId(idB.toUnqualifiedVersionless()).execute());
	}

	@Test
	void testTransactionIsScopedToTenant() {
		createPartition(4, "TENANT-D");
		createPartition(5, "TENANT-E");

		Bundle tx = new Bundle().setType(Bundle.BundleType.TRANSACTION);
		Patient pt = new Patient();
		pt.addName().setFamily("Family D");
		tx.addEntry().setResource(pt).getRequest().setMethod(Bundle.HTTPVerb.POST).setUrl("Patient");
		tenant.set("TENANT-D");
		ourClient.transaction().withBundle(tx).execute();

		assertEquals(1, searchPatients("TENANT-D").getEntry().size());
		assertEquals(0, searchPatients("TENANT-E").getEntry().size());
	}

	@Test
	void testMissingHeaderIsRejected() {
		tenant.set(); // no header
		assertThrows(InvalidRequestException.class,
			() -> ourClient.search().forResource(Patient.class).returnBundle(Bundle.class).execute());
	}

	@Test
	void testDuplicateHeaderIsRejected() {
		tenant.set("TENANT-A", "TENANT-B");
		assertThrows(InvalidRequestException.class,
			() -> ourClient.search().forResource(Patient.class).returnBundle(Bundle.class).execute());
	}

	@Test
	void testInvalidTenantIdIsRejected() {
		for (String bad : List.of("tenant a", "../x", "A".repeat(65), "a;b", "tenänt")) {
			tenant.set(bad);
			assertThrows(InvalidRequestException.class,
				() -> ourClient.search().forResource(Patient.class).returnBundle(Bundle.class).execute(), bad);
		}
	}
}
