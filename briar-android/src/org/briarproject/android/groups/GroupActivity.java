package org.briarproject.android.groups;

import static android.view.Gravity.CENTER_HORIZONTAL;
import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static android.widget.LinearLayout.VERTICAL;
import static java.util.logging.Level.INFO;
import static java.util.logging.Level.WARNING;
import static org.briarproject.android.groups.ReadGroupPostActivity.RESULT_PREV_NEXT;
import static org.briarproject.android.util.CommonLayoutParams.MATCH_MATCH;
import static org.briarproject.android.util.CommonLayoutParams.MATCH_WRAP_1;

import java.util.Collection;
import java.util.concurrent.Executor;
import java.util.logging.Logger;

import javax.inject.Inject;

import org.briarproject.R;
import org.briarproject.android.BriarActivity;
import org.briarproject.android.util.HorizontalBorder;
import org.briarproject.android.util.ListLoadingProgressBar;
import org.briarproject.api.Author;
import org.briarproject.api.android.DatabaseUiExecutor;
import org.briarproject.api.db.DatabaseComponent;
import org.briarproject.api.db.DbException;
import org.briarproject.api.db.MessageHeader;
import org.briarproject.api.db.NoSuchMessageException;
import org.briarproject.api.db.NoSuchSubscriptionException;
import org.briarproject.api.event.Event;
import org.briarproject.api.event.EventListener;
import org.briarproject.api.event.MessageAddedEvent;
import org.briarproject.api.event.MessageExpiredEvent;
import org.briarproject.api.event.SubscriptionRemovedEvent;
import org.briarproject.api.lifecycle.LifecycleManager;
import org.briarproject.api.messaging.GroupId;
import org.briarproject.api.messaging.MessageId;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;

public class GroupActivity extends BriarActivity implements EventListener,
OnClickListener, OnItemClickListener {

	private static final int REQUEST_READ = 2;
	private static final Logger LOG =
			Logger.getLogger(GroupActivity.class.getName());

	private String groupName = null;
	private GroupAdapter adapter = null;
	private ListView list = null;
	private ListLoadingProgressBar loading = null;

	// Fields that are accessed from background threads must be volatile
	@Inject private volatile DatabaseComponent db;
	@Inject @DatabaseUiExecutor private volatile Executor dbUiExecutor;
	@Inject private volatile LifecycleManager lifecycleManager;
	private volatile GroupId groupId = null;

	@Override
	public void onCreate(Bundle state) {
		super.onCreate(state);

		Intent i = getIntent();
		byte[] b = i.getByteArrayExtra("briar.GROUP_ID");
		if(b == null) throw new IllegalStateException();
		groupId = new GroupId(b);
		groupName = i.getStringExtra("briar.GROUP_NAME");
		if(groupName == null) throw new IllegalStateException();
		setTitle(groupName);

		LinearLayout layout = new LinearLayout(this);
		layout.setLayoutParams(MATCH_MATCH);
		layout.setOrientation(VERTICAL);
		layout.setGravity(CENTER_HORIZONTAL);

		adapter = new GroupAdapter(this);
		list = new ListView(this);
		// Give me all the width and all the unused height
		list.setLayoutParams(MATCH_WRAP_1);
		list.setAdapter(adapter);
		list.setOnItemClickListener(this);
		layout.addView(list);

		// Show a progress bar while the list is loading
		list.setVisibility(GONE);
		loading = new ListLoadingProgressBar(this);
		layout.addView(loading);

		layout.addView(new HorizontalBorder(this));

		ImageButton composeButton = new ImageButton(this);
		composeButton.setBackgroundResource(0);
		composeButton.setImageResource(R.drawable.content_new_email);
		composeButton.setOnClickListener(this);
		layout.addView(composeButton);

		setContentView(layout);
	}

	@Override
	public void onResume() {
		super.onResume();
		db.addListener(this);
		loadHeaders();
	}

	private void loadHeaders() {
		dbUiExecutor.execute(new Runnable() {
			public void run() {
				try {
					lifecycleManager.waitForDatabase();
					long now = System.currentTimeMillis();
					Collection<MessageHeader> headers =
							db.getMessageHeaders(groupId);
					long duration = System.currentTimeMillis() - now;
					if(LOG.isLoggable(INFO))
						LOG.info("Load took " + duration + " ms");
					displayHeaders(headers);
				} catch(NoSuchSubscriptionException e) {
					if(LOG.isLoggable(INFO)) LOG.info("Subscription removed");
					finishOnUiThread();
				} catch(DbException e) {
					if(LOG.isLoggable(WARNING))
						LOG.log(WARNING, e.toString(), e);
				} catch(InterruptedException e) {
					if(LOG.isLoggable(INFO))
						LOG.info("Interrupted while waiting for database");
					Thread.currentThread().interrupt();
				}
			}
		});
	}

	private void displayHeaders(final Collection<MessageHeader> headers) {
		runOnUiThread(new Runnable() {
			public void run() {
				list.setVisibility(VISIBLE);
				loading.setVisibility(GONE);
				adapter.clear();
				for(MessageHeader h : headers) adapter.add(new GroupItem(h));
				adapter.sort(GroupItemComparator.INSTANCE);
				adapter.notifyDataSetChanged();
				expandMessages();
			}
		});
	}

	private void expandMessages() {
		// Expand unread messages and the last three messages
		int firstExpanded = -1, count = adapter.getCount();
		if(count == 0) return;
		for(int i = 0; i < count; i++) {
			GroupItem item = adapter.getItem(i);
			if(!item.getHeader().isRead() || i >= count - 3) {
				if(firstExpanded == -1) firstExpanded = i;
				item.setExpanded(true);
				loadMessage(item.getHeader());
			}
		}
		// Scroll to the first expanded message
		list.setSelection(firstExpanded);
	}

	private void loadMessage(final MessageHeader h) {
		dbUiExecutor.execute(new Runnable() {
			public void run() {
				try {
					lifecycleManager.waitForDatabase();
					long now = System.currentTimeMillis();
					byte[] body = db.getMessageBody(h.getId());
					long duration = System.currentTimeMillis() - now;
					if(LOG.isLoggable(INFO))
						LOG.info("Loading message took " + duration + " ms");
					displayMessage(h.getId(), body);
					if(!h.isRead()) {
						now = System.currentTimeMillis();
						db.setReadFlag(h.getId(), true);
						duration = System.currentTimeMillis() - now;
						if(LOG.isLoggable(INFO))
							LOG.info("Setting read took " + duration + " ms");
					}
				} catch(NoSuchMessageException e) {
					if(LOG.isLoggable(INFO)) LOG.info("Message expired");
					// The item will be removed when we get the event
				} catch(DbException e) {
					if(LOG.isLoggable(WARNING))
						LOG.log(WARNING, e.toString(), e);
				} catch(InterruptedException e) {
					if(LOG.isLoggable(INFO))
						LOG.info("Interrupted while waiting for database");
					Thread.currentThread().interrupt();
				}
			}
		});
	}

	private void displayMessage(final MessageId m, final byte[] body) {
		runOnUiThread(new Runnable() {
			public void run() {
				int count = adapter.getCount();
				for(int i = 0; i < count; i++) {
					GroupItem item = adapter.getItem(i);
					if(item.getHeader().getId().equals(m)) {
						item.setBody(body);
						adapter.notifyDataSetChanged();
						return;
					}
				}
			}
		});
	}

	private void finishOnUiThread() {
		runOnUiThread(new Runnable() {
			public void run() {
				finish();
			}
		});
	}

	@Override
	protected void onActivityResult(int request, int result, Intent data) {
		super.onActivityResult(request, result, data);
		if(request == REQUEST_READ && result == RESULT_PREV_NEXT) {
			int position = data.getIntExtra("briar.POSITION", -1);
			if(position >= 0 && position < adapter.getCount())
				displayMessage(position);
		}
	}

	@Override
	public void onPause() {
		super.onPause();
		db.removeListener(this);
	}

	public void eventOccurred(Event e) {
		if(e instanceof MessageAddedEvent) {
			if(((MessageAddedEvent) e).getGroup().getId().equals(groupId)) {
				if(LOG.isLoggable(INFO)) LOG.info("Message added, reloading");
				loadHeaders();
			}
		} else if(e instanceof MessageExpiredEvent) {
			if(LOG.isLoggable(INFO)) LOG.info("Message expired, reloading");
			loadHeaders();
		} else if(e instanceof SubscriptionRemovedEvent) {
			SubscriptionRemovedEvent s = (SubscriptionRemovedEvent) e;
			if(s.getGroup().getId().equals(groupId)) {
				if(LOG.isLoggable(INFO)) LOG.info("Subscription removed");
				finishOnUiThread();
			}
		}
	}

	public void onClick(View view) {
		Intent i = new Intent(this, WriteGroupPostActivity.class);
		i.putExtra("briar.GROUP_ID", groupId.getBytes());
		startActivity(i);
	}

	public void onItemClick(AdapterView<?> parent, View view, int position,
			long id) {
		displayMessage(position);
	}

	private void displayMessage(int position) {
		MessageHeader item = adapter.getItem(position).getHeader();
		Intent i = new Intent(this, ReadGroupPostActivity.class);
		i.putExtra("briar.GROUP_ID", groupId.getBytes());
		i.putExtra("briar.GROUP_NAME", groupName);
		i.putExtra("briar.MESSAGE_ID", item.getId().getBytes());
		Author author = item.getAuthor();
		if(author != null) i.putExtra("briar.AUTHOR_NAME", author.getName());
		i.putExtra("briar.AUTHOR_STATUS", item.getAuthorStatus().name());
		i.putExtra("briar.CONTENT_TYPE", item.getContentType());
		i.putExtra("briar.TIMESTAMP", item.getTimestamp());
		i.putExtra("briar.POSITION", position);
		startActivityForResult(i, REQUEST_READ);
	}
}
