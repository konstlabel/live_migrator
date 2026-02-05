package migrator.crac;

import migrator.exceptions.MigrateException;

/**
 * Default no-op CracController.
 *
 * Implemented as enum-singleton intentionally:
 * - stateless
 * - thread-safe
 * - safe against reflection/serialization
 */
public enum NoopCracController implements CracController {
    INSTANCE;

    @Override
    public void deleteCheckpoint() { /* no-op */ }

    @Override
    public void restoreFromCheckpoint() throws MigrateException {
        throw new UnsupportedOperationException("CRaC restore not supported in NoopCracController");
    }
}
