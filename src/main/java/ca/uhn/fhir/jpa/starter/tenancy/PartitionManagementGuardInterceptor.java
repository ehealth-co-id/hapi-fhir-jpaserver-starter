package ca.uhn.fhir.jpa.starter.tenancy;

import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.exceptions.ForbiddenOperationException;

/**
 * Only the DEFAULT tenant may call the {@code $partition-management-*} operations, so a regular tenant cannot
 * list, create, update or delete other tenants' partitions.
 */
@Interceptor
public class PartitionManagementGuardInterceptor {

	private static final String OPERATION_PREFIX = "$partition-management";

	@Hook(Pointcut.SERVER_INCOMING_REQUEST_PRE_HANDLED)
	public void check(RequestDetails theRequestDetails) {
		String operation = theRequestDetails.getOperation();
		if (operation != null && operation.startsWith(OPERATION_PREFIX)
				&& !"DEFAULT".equals(theRequestDetails.getTenantId())) {
			throw new ForbiddenOperationException("Operation not permitted for this tenant");
		}
	}
}
