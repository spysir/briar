package org.briarproject.bramble.api.cleanup;

import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.db.Transaction;
import org.briarproject.bramble.api.nullsafety.NotNullByDefault;
import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.bramble.api.sync.MessageId;

@NotNullByDefault
public interface CleanupHook {

	boolean deleteMessage(Transaction txn, GroupId g, MessageId m)
			throws DbException;
}