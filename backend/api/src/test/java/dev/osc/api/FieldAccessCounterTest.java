package dev.osc.api;

import dev.osc.metadata.performance.FieldAccessCounter;
import dev.osc.metadata.performance.HotFieldReport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("FieldAccessCounter")
class FieldAccessCounterTest {

    private final FieldAccessCounter counter = new FieldAccessCounter();

    @Test
    @DisplayName("records field access and returns correct count")
    void recordsFieldAccessCount() {
        UUID tenantId = UUID.randomUUID();
        String objectName = "Account";
        String fieldName = "name";

        counter.record(tenantId, objectName, fieldName);
        counter.record(tenantId, objectName, fieldName);
        counter.record(tenantId, objectName, fieldName);

        HotFieldReport report = counter.topFields(tenantId, objectName, 10);
        assertThat(report.fields()).hasSize(1);
        assertThat(report.fields().get(0).fieldApiName()).isEqualTo(fieldName);
        assertThat(report.fields().get(0).hitCount()).isEqualTo(3L);
    }

    @Test
    @DisplayName("returns fields sorted by hitCount descending")
    void returnsSortedByHitCountDescending() {
        UUID tenantId = UUID.randomUUID();
        String objectName = "Contact";

        counter.record(tenantId, objectName, "email");
        counter.record(tenantId, objectName, "email");
        counter.record(tenantId, objectName, "email");
        counter.record(tenantId, objectName, "phone");
        counter.record(tenantId, objectName, "phone");
        counter.record(tenantId, objectName, "firstName");

        HotFieldReport report = counter.topFields(tenantId, objectName, 10);
        List<HotFieldReport.FieldHit> fields = report.fields();

        assertThat(fields).hasSize(3);
        assertThat(fields.get(0).fieldApiName()).isEqualTo("email");
        assertThat(fields.get(0).hitCount()).isEqualTo(3L);
        assertThat(fields.get(1).fieldApiName()).isEqualTo("phone");
        assertThat(fields.get(1).hitCount()).isEqualTo(2L);
        assertThat(fields.get(2).fieldApiName()).isEqualTo("firstName");
        assertThat(fields.get(2).hitCount()).isEqualTo(1L);
    }

    @Test
    @DisplayName("limits report to top-N fields")
    void limitsToTopN() {
        UUID tenantId = UUID.randomUUID();
        String objectName = "Lead";

        for (int i = 0; i < 15; i++) {
            for (int j = 0; j <= i; j++) {
                counter.record(tenantId, objectName, "field" + i);
            }
        }

        HotFieldReport report = counter.topFields(tenantId, objectName, 10);
        assertThat(report.fields()).hasSize(10);
    }

    @Test
    @DisplayName("different tenants have isolated counters")
    void tenantsAreIsolated() {
        UUID tenant1 = UUID.randomUUID();
        UUID tenant2 = UUID.randomUUID();
        String objectName = "Opportunity";

        counter.record(tenant1, objectName, "amount");
        counter.record(tenant1, objectName, "amount");

        counter.record(tenant2, objectName, "amount");

        HotFieldReport report1 = counter.topFields(tenant1, objectName, 10);
        HotFieldReport report2 = counter.topFields(tenant2, objectName, 10);

        assertThat(report1.fields().get(0).hitCount()).isEqualTo(2L);
        assertThat(report2.fields().get(0).hitCount()).isEqualTo(1L);
    }

    @Test
    @DisplayName("returns empty report for unknown object")
    void emptyReportForUnknownObject() {
        UUID tenantId = UUID.randomUUID();
        HotFieldReport report = counter.topFields(tenantId, "NonExistent", 10);
        assertThat(report.fields()).isEmpty();
    }
}
