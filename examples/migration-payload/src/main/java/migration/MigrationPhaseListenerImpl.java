package migration;

import migrator.annotations.PhaseListener;
import migrator.phase.MigrationContext;
import migrator.phase.MigrationPhaseListener;

@PhaseListener
public class MigrationPhaseListenerImpl implements MigrationPhaseListener {

    @Override
    public void onBeforeCriticalPhase(MigrationContext ctx) {
        System.out.println("[MIGRATION] Entering critical phase, id=" + ctx.migrationId());
    }

    @Override
    public void onAfterCriticalPhase(MigrationContext ctx) {
        System.out.println("[MIGRATION] Exiting critical phase, id=" + ctx.migrationId());
    }
}
