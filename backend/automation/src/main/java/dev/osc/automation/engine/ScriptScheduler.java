package dev.osc.automation.engine;

import dev.osc.security.UserContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Component
public class ScriptScheduler {

    private final ScriptAutomationRepository scriptAutomationRepository;
    private final UserCodeExecutor kotlinScriptExecutor;

    public ScriptScheduler(ScriptAutomationRepository scriptAutomationRepository,
                           @Qualifier("kotlinScriptExecutor") UserCodeExecutor kotlinScriptExecutor) {
        this.scriptAutomationRepository = scriptAutomationRepository;
        this.kotlinScriptExecutor = kotlinScriptExecutor;
    }

    @Scheduled(cron = "0 * * * * *") // run every minute
    public void schedule() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MINUTES);
        Instant prev = now.minusSeconds(1);

        scriptAutomationRepository.findAllTenantIds()
                .flatMap(tenantId -> scriptAutomationRepository.findActiveScheduledAndBatch(tenantId)
                        .flatMap(def -> {
                            String cron = def.scheduleCron();
                            if (cron == null || cron.isBlank()) {
                                return Mono.empty();
                            }
                            try {
                                CronExpression cronExpression = CronExpression.parse(cron);
                                Instant next = cronExpression.next(prev);
                                if (next != null && next.truncatedTo(ChronoUnit.MINUTES).equals(now)) {
                                    // Trigger execution on boundedElastic
                                    return Mono.fromCallable(() -> {
                                        Map<String, Object> executionContext = new HashMap<>();
                                        executionContext.put("tenantId", tenantId);
                                        executionContext.put("currentUser", new UserContext(
                                                UUID.fromString("00000000-0000-0000-0000-000000000000"),
                                                tenantId
                                        ));
                                        executionContext.put("triggerEvent", def.kind());
                                        executionContext.put("scriptId", def.id());
                                        executionContext.put("timeoutSeconds", def.timeoutSeconds());
                                        if (def.objectApiName() != null) {
                                            executionContext.put("objectApiName", def.objectApiName());
                                        }

                                        return kotlinScriptExecutor.execute(def.source(), executionContext);
                                    })
                                    .subscribeOn(Schedulers.boundedElastic())
                                    .then();
                                }
                            } catch (Exception e) {
                                // invalid cron expression, skip
                            }
                            return Mono.empty();
                        })
                )
                .subscribe();
    }
}
