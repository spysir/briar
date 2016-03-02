package org.briarproject.clients;

import com.google.inject.AbstractModule;

import org.briarproject.api.clients.ClientHelper;
import org.briarproject.api.clients.PrivateGroupFactory;

public class ClientsModule extends AbstractModule {

	@Override
	protected void configure() {
		bind(ClientHelper.class).to(ClientHelperImpl.class);
		bind(PrivateGroupFactory.class).to(PrivateGroupFactoryImpl.class);
	}
}