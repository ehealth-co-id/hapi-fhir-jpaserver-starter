package ca.uhn.fhir.jpa.starter;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.client.api.IClientInterceptor;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.client.api.IHttpRequest;
import ca.uhn.fhir.rest.client.api.IHttpResponse;
import ca.uhn.fhir.rest.client.api.ServerValidationModeEnum;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import ca.uhn.fhir.rest.server.provider.ProviderConstants;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The DEFAULT (admin) tenant is selected by a long secret that is only configured as its SHA-256 digest.
 */
@ExtendWith(SpringExtension.class)
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = {Application.class}, properties =
	{
		"spring.datasource.url=jdbc:h2:mem:dbr4-mt-secret",
		"spring.jpa.properties.hibernate.search.backend.directory.type=local-heap",
		"hapi.fhir.fhir_version=r4",
		"hapi.fhir.cr_enabled=false",
		"hapi.fhir.partitioning.partitioning_include_in_search_hashes=false",
		"hapi.fhir.partitioning.request_tenant_partitioning_mode=true",
		"hapi.fhir.partitioning.default_tenant_header_value_sha256=7a3871e9a5bb4aea71e94a6aa5b6dad56f1436c2b7f956bf69f5b72e4c294be5",
	})
class MultitenantDefaultSecretR4IT {

	private static final String SECRET = "AGPe3CtNLSNX1eunNxIRyTLkDCaVCTr3wSximVlkxPK0t8fITktvOpKq2lWrv3sku0ctt5qnEWm5zkhmaeVr0r";

	private String tenant;
	private IGenericClient client;
	@LocalServerPort
	private int port;

	@BeforeEach
	void beforeEach() {
		FhirContext ctx = FhirContext.forR4();
		ctx.getRestfulClientFactory().setServerValidationMode(ServerValidationModeEnum.NEVER);
		client = ctx.newRestfulGenericClient("http://localhost:" + port + "/fhir/");
		client.registerInterceptor(new IClientInterceptor() {
			@Override
			public void interceptRequest(IHttpRequest theRequest) {
				theRequest.addHeader("X-TENANT", tenant);
			}

			@Override
			public void interceptResponse(IHttpResponse theResponse) {
				// nothing
			}
		});
	}

	@Test
	void testLongSecretSelectsDefaultTenantAndCanManagePartitions() {
		tenant = SECRET;
		client.operation().onServer().named(ProviderConstants.PARTITION_MANAGEMENT_CREATE_PARTITION)
			.withParameter(Parameters.class, ProviderConstants.PARTITION_MANAGEMENT_PARTITION_ID, new IntegerType(1))
			.andParameter(ProviderConstants.PARTITION_MANAGEMENT_PARTITION_NAME, new CodeType("TENANT-A"))
			.execute();

		tenant = "TENANT-A";
		client.create().resource(new Patient()).execute();
		assertEquals(1, client.search().forResource(Patient.class).returnBundle(Bundle.class).execute().getEntry().size());
	}

	@Test
	void testLiteralDefaultAndWrongSecretAreRejected() {
		for (String bad : new String[] {"DEFAULT", SECRET + "x", SECRET.substring(1)}) {
			tenant = bad;
			assertThrows(InvalidRequestException.class,
				() -> client.search().forResource(Patient.class).returnBundle(Bundle.class).execute(), bad.length() + " chars");
		}
	}
}
