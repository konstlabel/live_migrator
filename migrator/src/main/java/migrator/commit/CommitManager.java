package migrator.commit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import migrator.crac.CracController;
import migrator.exceptions.MigrateException;

import java.util.Objects;

/**
 * Manages the commit phase of migration by deleting checkpoints
 * and invoking optional post-commit hooks.
 *
 * <p>After a successful migration, the CommitManager:
 * <ol>
 *   <li>Deletes the checkpoint (making rollback impossible)</li>
 *   <li>Invokes the optional {@link CommitListener}</li>
 * </ol>
 *
 * @see RollbackManager
 * @see CracController
 */
public class CommitManager {

    private static final Logger log = LoggerFactory.getLogger(CommitManager.class);

    private final CracController cracController;
    private final CommitListener listener;

    /**
     * Listener for post-commit notifications.
     */
    public interface CommitListener {
        void onCommit();
    }

    public CommitManager(CracController cracController, CommitListener listener) {
        this.cracController = Objects.requireNonNull(cracController);
        this.listener = listener; // may be null
    }

    public CommitManager(CracController cracController) {
        this(cracController, null);
    }

    /**
     * Commit the migration by deleting the checkpoint.
     * After this, rollback is no longer possible.
     *
     * @throws MigrateException if checkpoint deletion fails
     */
    public void commit() throws MigrateException {
        cracController.deleteCheckpoint();
        if (listener != null) {
            try {
                listener.onCommit();
            } catch (Exception e) {
                // listener failure shouldn't make commit fail — log and continue
                log.warn("Commit listener threw exception (ignored)", e);
            }
        }
    }
}
