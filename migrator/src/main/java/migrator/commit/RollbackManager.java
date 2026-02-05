package migrator.commit;

import migrator.crac.CracController;
import migrator.exceptions.MigrateException;

import java.util.Objects;

/**
 * Manages rollback by restoring from checkpoint via {@link CracController}.
 *
 * <p><strong>Warning:</strong> The {@link #rollback()} method may not return
 * if the checkpoint restore replaces the current process.
 *
 * @see CommitManager
 * @see CracController
 */
public class RollbackManager {

    private final CracController cracController;

    public RollbackManager(CracController cracController) {
        this.cracController = Objects.requireNonNull(cracController);
    }

    /**
     * Attempt to restore from checkpoint.
     *
     * <p><strong>Warning:</strong> This method may not return if the restore
     * replaces the current process. If it does return, it throws an exception.
     *
     * @throws MigrateException if rollback fails
     */
    public void rollback() throws MigrateException {
        try {
            cracController.restoreFromCheckpoint();
            // If restoreFromCheckpoint returns normally, that means the implementation didn't
            // actually restore the process (rare). We consider that an error in this context.
            throw new MigrateException("CRaC restore did not occur (restoreFromCheckpoint returned)");
        } catch (MigrateException me) {
            throw me;
        } catch (Exception e) {
            throw new MigrateException("Rollback failed: " + e.getMessage(), e);
        }
    }
}
