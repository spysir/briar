package org.briarproject.bramble.plugin.file;

import org.briarproject.bramble.api.contact.ContactId;
import org.briarproject.bramble.api.db.DatabaseComponent;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.lifecycle.IoExecutor;
import org.briarproject.bramble.api.nullsafety.NotNullByDefault;
import org.briarproject.bramble.api.plugin.file.RemovableDriveManager;
import org.briarproject.bramble.api.plugin.file.RemovableDriveTask;
import org.briarproject.bramble.api.properties.TransportProperties;
import org.briarproject.bramble.api.properties.TransportPropertyManager;

import java.util.concurrent.Executor;

import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.ThreadSafe;
import javax.inject.Inject;

import static org.briarproject.bramble.api.plugin.file.RemovableDriveConstants.ID;
import static org.briarproject.bramble.api.plugin.file.RemovableDriveConstants.PROP_SUPPORTED;
import static org.briarproject.bramble.plugin.file.RemovableDrivePluginFactory.MAX_LATENCY;

@ThreadSafe
@NotNullByDefault
class RemovableDriveManagerImpl
		implements RemovableDriveManager, RemovableDriveTaskRegistry {

	private final Executor ioExecutor;
	private final DatabaseComponent db;
	private final TransportPropertyManager transportPropertyManager;
	private final RemovableDriveTaskFactory taskFactory;
	private final Object lock = new Object();

	@GuardedBy("lock")
	private RemovableDriveTask reader = null;
	@GuardedBy("lock")
	private RemovableDriveTask writer = null;

	@Inject
	RemovableDriveManagerImpl(
			@IoExecutor Executor ioExecutor,
			DatabaseComponent db,
			TransportPropertyManager transportPropertyManager,
			RemovableDriveTaskFactory taskFactory) {
		this.ioExecutor = ioExecutor;
		this.db = db;
		this.transportPropertyManager = transportPropertyManager;
		this.taskFactory = taskFactory;
	}

	@Nullable
	@Override
	public RemovableDriveTask getCurrentReaderTask() {
		synchronized (lock) {
			return reader;
		}
	}

	@Nullable
	@Override
	public RemovableDriveTask getCurrentWriterTask() {
		synchronized (lock) {
			return writer;
		}
	}

	@Override
	public RemovableDriveTask startReaderTask(TransportProperties p) {
		RemovableDriveTask created;
		synchronized (lock) {
			if (reader != null) return reader;
			reader = created = taskFactory.createReader(this, p);
		}
		ioExecutor.execute(created);
		return created;
	}

	@Override
	public RemovableDriveTask startWriterTask(ContactId c,
			TransportProperties p) {
		RemovableDriveTask created;
		synchronized (lock) {
			if (writer != null) return writer;
			writer = created = taskFactory.createWriter(this, c, p);
		}
		ioExecutor.execute(created);
		return created;
	}

	@Override
	public boolean isTransportSupportedByContact(ContactId c)
			throws DbException {
		TransportProperties p =
				transportPropertyManager.getRemoteProperties(c, ID);
		return "true".equals(p.get(PROP_SUPPORTED));
	}

	@Override
	public boolean isWriterTaskNeeded(ContactId c) throws DbException {
		return db.transactionWithResult(true, txn ->
				db.containsAnythingToSend(txn, c, MAX_LATENCY, true));
	}

	@Override
	public void removeReader(RemovableDriveTask task) {
		synchronized (lock) {
			if (reader == task) reader = null;
		}
	}

	@Override
	public void removeWriter(RemovableDriveTask task) {
		synchronized (lock) {
			if (writer == task) writer = null;
		}
	}
}
