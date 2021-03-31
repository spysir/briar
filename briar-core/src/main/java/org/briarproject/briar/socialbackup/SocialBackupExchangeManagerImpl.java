package org.briarproject.briar.socialbackup;

import org.briarproject.bramble.api.FormatException;
import org.briarproject.bramble.api.Predicate;
import org.briarproject.bramble.api.client.ClientHelper;
import org.briarproject.bramble.api.contact.ContactExchangeConstants;
import org.briarproject.bramble.api.contact.ContactExchangeManager;
import org.briarproject.bramble.api.contact.ContactExchangeRecordTypes;
import org.briarproject.bramble.api.contact.ContactManager;
import org.briarproject.bramble.api.crypto.PublicKey;
import org.briarproject.bramble.api.crypto.SecretKey;
import org.briarproject.bramble.api.data.BdfDictionary;
import org.briarproject.bramble.api.data.BdfList;
import org.briarproject.bramble.api.db.DatabaseComponent;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.identity.IdentityManager;
import org.briarproject.bramble.api.identity.LocalAuthor;
import org.briarproject.bramble.api.nullsafety.NotNullByDefault;
import org.briarproject.bramble.api.plugin.TransportId;
import org.briarproject.bramble.api.plugin.duplex.DuplexTransportConnection;
import org.briarproject.bramble.api.properties.TransportProperties;
import org.briarproject.bramble.api.properties.TransportPropertyManager;
import org.briarproject.bramble.api.record.Record;
import org.briarproject.bramble.api.record.RecordReader;
import org.briarproject.bramble.api.record.RecordReaderFactory;
import org.briarproject.bramble.api.record.RecordWriter;
import org.briarproject.bramble.api.record.RecordWriterFactory;
import org.briarproject.bramble.api.system.Clock;
import org.briarproject.bramble.api.transport.StreamReaderFactory;
import org.briarproject.bramble.api.transport.StreamWriter;
import org.briarproject.bramble.api.transport.StreamWriterFactory;
import org.briarproject.bramble.api.contact.ContactExchangeCrypto;
import org.briarproject.briar.api.socialbackup.ReturnShardPayload;
import org.briarproject.briar.api.socialbackup.Shard;
import org.briarproject.briar.api.socialbackup.BackupPayload;
import org.briarproject.briar.api.socialbackup.SocialBackupExchangeManager;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;
import java.util.logging.Logger;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import javax.inject.Inject;

import sun.java2d.xr.XRBackendNative;

import static java.util.logging.Logger.getLogger;
import static org.briarproject.bramble.api.identity.AuthorConstants.MAX_SIGNATURE_LENGTH;
import static org.briarproject.bramble.util.ValidationUtils.checkLength;
import static org.briarproject.bramble.util.ValidationUtils.checkSize;

@Immutable
@NotNullByDefault
class SocialBackupExchangeManagerImpl implements SocialBackupExchangeManager {

	private static final Logger LOG =
			getLogger(SocialBackupExchangeManagerImpl.class.getName());

	// Accept records with current protocol version, known record type
	private static final Predicate<Record> ACCEPT = r ->
			r.getProtocolVersion() ==
					ContactExchangeConstants.PROTOCOL_VERSION &&
					isKnownRecordType(r.getRecordType());

	// Ignore records with current protocol version, unknown record type
	private static final Predicate<Record> IGNORE = r ->
			r.getProtocolVersion() ==
					ContactExchangeConstants.PROTOCOL_VERSION &&
					!isKnownRecordType(r.getRecordType());

	private static boolean isKnownRecordType(byte type) {
		return type == ContactExchangeRecordTypes.CONTACT_INFO;
	}

	private final DatabaseComponent db;
	private final ClientHelper clientHelper;
	private final RecordReaderFactory recordReaderFactory;
	private final RecordWriterFactory recordWriterFactory;
	private final Clock clock;
	private final ContactManager contactManager;
	//	private final IdentityManager identityManager;
	private final TransportPropertyManager transportPropertyManager;
	private final ContactExchangeCrypto contactExchangeCrypto;
	private final StreamReaderFactory streamReaderFactory;
	private final StreamWriterFactory streamWriterFactory;

	@Inject
	SocialBackupExchangeManagerImpl(DatabaseComponent db,
			ClientHelper clientHelper,
			RecordReaderFactory recordReaderFactory,
			RecordWriterFactory recordWriterFactory, Clock clock,
			ContactManager contactManager,
//			IdentityManager identityManager,
			TransportPropertyManager transportPropertyManager,
			ContactExchangeCrypto contactExchangeCrypto,
			StreamReaderFactory streamReaderFactory,
			StreamWriterFactory streamWriterFactory) {
		this.db = db;
		this.clientHelper = clientHelper;
		this.recordReaderFactory = recordReaderFactory;
		this.recordWriterFactory = recordWriterFactory;
		this.clock = clock;
		this.contactManager = contactManager;
//		this.identityManager = identityManager;
		this.transportPropertyManager = transportPropertyManager;
		this.contactExchangeCrypto = contactExchangeCrypto;
		this.streamReaderFactory = streamReaderFactory;
		this.streamWriterFactory = streamWriterFactory;
	}

	@Override
	public void sendReturnShard(DuplexTransportConnection conn,
			SecretKey masterKey,
			boolean verified) throws IOException, DbException {
		boolean alice = true;
		// Get the transport connection's input and output streams
		InputStream in = conn.getReader().getInputStream();
		OutputStream out = conn.getWriter().getOutputStream();

		// Get the local author and transport properties
//		LocalAuthor localAuthor = identityManager.getLocalAuthor();
		Map<TransportId, TransportProperties> localProperties =
				transportPropertyManager.getLocalProperties();

		// Derive the header keys for the transport streams
		SecretKey localHeaderKey =
				contactExchangeCrypto.deriveHeaderKey(masterKey, alice);
		SecretKey remoteHeaderKey =
				contactExchangeCrypto.deriveHeaderKey(masterKey, !alice);

		// Create the readers
		InputStream streamReader = streamReaderFactory
				.createContactExchangeStreamReader(in, remoteHeaderKey);
		RecordReader recordReader =
				recordReaderFactory.createRecordReader(streamReader);

		// Create the writers
		StreamWriter streamWriter = streamWriterFactory
				.createContactExchangeStreamWriter(out, localHeaderKey);
		RecordWriter recordWriter = recordWriterFactory
				.createRecordWriter(streamWriter.getOutputStream());

		// Create our signature
//		byte[] localSignature = contactExchangeCrypto
//				.sign(localAuthor.getPrivateKey(), masterKey, alice);

		// Exchange contact info
		long localTimestamp = clock.currentTimeMillis();

		sendShardPayload(recordWriter, localProperties, returnShardPayload, localTimestamp);
		receiveAcknowledgement(recordReader);

		// Send EOF on the outgoing stream
		streamWriter.sendEndOfStream();

		// Skip any remaining records from the incoming stream
		recordReader.readRecord(r -> false, IGNORE);

		// Verify the contact's signature
//		PublicKey remotePublicKey = remoteInfo.author.getPublicKey();
//		if (!contactExchangeCrypto.verify(remotePublicKey,
//				masterKey, !alice, remoteInfo.signature)) {
//			LOG.warning("Invalid signature");
//			throw new FormatException();
//		}

		// The agreed timestamp is the minimum of the peers' timestamps
//		long timestamp = Math.min(localTimestamp, remoteInfo.timestamp);

		LOG.info("Social backup sent");
	}

	@Override
	public ReturnShardPayload receiveReturnShard(DuplexTransportConnection conn,
			SecretKey masterKey, boolean verified)
			throws IOException, DbException {
		boolean alice = false;
		// Get the transport connection's input and output streams
		InputStream in = conn.getReader().getInputStream();
		OutputStream out = conn.getWriter().getOutputStream();

		// Get the local author and transport properties
//		LocalAuthor localAuthor = identityManager.getLocalAuthor();
		Map<TransportId, TransportProperties> localProperties =
				transportPropertyManager.getLocalProperties();

		// Derive the header keys for the transport streams
		SecretKey localHeaderKey =
				contactExchangeCrypto.deriveHeaderKey(masterKey, alice);
		SecretKey remoteHeaderKey =
				contactExchangeCrypto.deriveHeaderKey(masterKey, !alice);

		// Create the readers
		InputStream streamReader = streamReaderFactory
				.createContactExchangeStreamReader(in, remoteHeaderKey);
		RecordReader recordReader =
				recordReaderFactory.createRecordReader(streamReader);

		// Create the writers
		StreamWriter streamWriter = streamWriterFactory
				.createContactExchangeStreamWriter(out, localHeaderKey);
		RecordWriter recordWriter = recordWriterFactory
				.createRecordWriter(streamWriter.getOutputStream());

		// Create our signature
//		byte[] localSignature = contactExchangeCrypto
//				.sign(localAuthor.getPrivateKey(), masterKey, alice);

		// Exchange contact info
		long localTimestamp = clock.currentTimeMillis();
		ReturnShardPayload returnShardPayload =
				receiveShardPayload(recordReader);
		sendAcknowledgement(recordWriter, localProperties, localTimestamp);

		// Send EOF on the outgoing stream
		streamWriter.sendEndOfStream();

		// Skip any remaining records from the incoming stream
		recordReader.readRecord(r -> false, IGNORE);

		// Verify the contact's signature
//		PublicKey remotePublicKey = remoteInfo.author.getPublicKey();
//		if (!contactExchangeCrypto.verify(remotePublicKey,
//				masterKey, !alice, remoteInfo.signature)) {
//			LOG.warning("Invalid signature");
//			throw new FormatException();
//		}

		// The agreed timestamp is the minimum of the peers' timestamps
//		long timestamp = Math.min(localTimestamp, remoteInfo.timestamp);

		// Contact exchange succeeded
		LOG.info("Received shard payload");
		return returnShardPayload;
	}

	private void sendShardPayload(RecordWriter recordWriter,
			Map<TransportId, TransportProperties> properties,
			ReturnShardPayload returnShardPayload,
			long timestamp) throws IOException {
//		BdfList authorList = clientHelper.toList(author);
		BdfDictionary props = clientHelper.toDictionary(properties);
		Shard shard = returnShardPayload.getShard();
		BdfList shardList = BdfList.of(shard.getSecretId(), shard.getShard());
		BdfList payload = BdfList.of(shardList,
				returnShardPayload.getBackupPayload().getBytes(), timestamp);
		recordWriter.writeRecord(new Record(
				ContactExchangeConstants.PROTOCOL_VERSION,
				SocialBackupExchangeRecordTypes.RETURN_SHARD,
				clientHelper.toByteArray(payload)));
		recordWriter.flush();
		LOG.info("Sent shard and encrypted backup");
	}

	private ReturnShardPayload receiveShardPayload(RecordReader recordReader)
			throws IOException {
		Record record = recordReader.readRecord(ACCEPT, IGNORE);
		if (record == null) throw new EOFException();
		LOG.info("Received shard and encrypted backup");
		BdfList payload = clientHelper.toList(record.getPayload());
		checkSize(payload, 3);
		BdfList shardList = payload.getList(0);
		Shard shard = new Shard(shardList.getRaw(0), shardList.getRaw(1));
		BackupPayload backupPayload = new BackupPayload(payload.getRaw(1));
//		Map<TransportId, TransportProperties> properties =
//				clientHelper.parseAndValidateTransportPropertiesMap(props);
//		byte[] signature = payload.getRaw(2);
//		checkLength(signature, 1, MAX_SIGNATURE_LENGTH);
		long timestamp = payload.getLong(2);
		if (timestamp < 0) throw new FormatException();
		return new ReturnShardPayload(shard, backupPayload);
	}

	private void sendAcknowledgement(RecordWriter recordWriter,
			Map<TransportId, TransportProperties> properties,
			long timestamp) throws IOException {

		BdfList payload = BdfList.of(timestamp);

		recordWriter.writeRecord(new Record(
				ContactExchangeConstants.PROTOCOL_VERSION,
				SocialBackupExchangeRecordTypes.ACKNOWLEDGEMENT,
				clientHelper.toByteArray(payload)));
		recordWriter.flush();
	}

	private void receiveAcknowledgement(RecordReader recordReader)
			throws IOException {
		Record record = recordReader.readRecord(ACCEPT, IGNORE);
		if (record == null) throw new EOFException();
		BdfList payload = clientHelper.toList(record.getPayload());
		checkSize(payload, 1);
		long timestamp = payload.getLong(0);
		if (timestamp < 0) throw new FormatException();
	}
}