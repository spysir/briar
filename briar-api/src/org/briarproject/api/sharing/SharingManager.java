package org.briarproject.api.sharing;

import org.briarproject.api.clients.ReadableMessageManager;
import org.briarproject.api.contact.Contact;
import org.briarproject.api.contact.ContactId;
import org.briarproject.api.db.DbException;
import org.briarproject.api.sync.ClientId;
import org.briarproject.api.sync.GroupId;
import org.briarproject.api.sync.MessageId;

import java.util.Collection;

public interface SharingManager<S extends Shareable, IM extends InvitationMessage>
		extends ReadableMessageManager {

	/** Returns the unique ID of the group sharing client. */
	ClientId getClientId();

	/**
	 * Sends an invitation to share the given shareable with the given contact
	 * and sends an optional message along with it.
	 */
	void sendInvitation(GroupId groupId, ContactId contactId,
			String message)	throws DbException;

	/**
	 * Responds to a pending group invitation
	 */
	void respondToInvitation(S s, Contact c, boolean accept)
			throws DbException;

	/**
	 * Returns all group sharing messages sent by the Contact
	 * identified by contactId.
	 */
	Collection<IM> getInvitationMessages(
			ContactId contactId) throws DbException;

	/**
	 * Returns a specific group sharing message sent by the Contact
	 * identified by contactId.
	 */
	IM getInvitationMessage(ContactId contactId, MessageId messageId)
			throws DbException;

	/** Returns all shareables to which the user has been invited. */
	Collection<S> getInvited() throws DbException;

	/** Returns all contacts who are sharing the given group with us. */
	Collection<Contact> getSharedBy(GroupId g) throws DbException;

	/** Returns the IDs of all contacts with whom the given group is shared. */
	Collection<Contact> getSharedWith(GroupId g) throws DbException;

	/** Returns true if the group not already shared and no invitation is open */
	boolean canBeShared(GroupId g, Contact c) throws DbException;

}