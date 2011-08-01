/*******************************************************************************
 * Copyright (c) 2009, 2010 Fraunhofer IWU and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Fraunhofer IWU - initial API and implementation
 *******************************************************************************/
package net.enilink.komma.owlim;

import info.aduna.io.FileUtil;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.Properties;

import org.eclipse.core.runtime.FileLocator;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Platform;
import net.enilink.composition.annotations.Iri;
import org.openrdf.model.Graph;
import org.openrdf.model.Resource;
import org.openrdf.model.Statement;
import org.openrdf.model.impl.GraphImpl;
import org.openrdf.model.vocabulary.RDF;
import org.openrdf.repository.Repository;
import org.openrdf.repository.RepositoryConnection;
import org.openrdf.repository.RepositoryException;
import org.openrdf.repository.config.RepositoryConfig;
import org.openrdf.repository.config.RepositoryConfigSchema;
import org.openrdf.repository.config.RepositoryRegistry;
import org.openrdf.repository.manager.LocalRepositoryManager;
import org.openrdf.repository.manager.RepositoryManager;
import org.openrdf.repository.sail.config.SailRepositoryFactory;
import org.openrdf.rio.RDFFormat;
import org.openrdf.rio.RDFParseException;
import org.openrdf.rio.RDFParser;
import org.openrdf.rio.Rio;
import org.openrdf.rio.helpers.StatementCollector;
import org.openrdf.sail.config.SailRegistry;

import com.google.inject.AbstractModule;
import com.google.inject.Module;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.ontotext.trree.owlim_ext.config.OWLIMSailFactory;

import net.enilink.komma.KommaCore;
import net.enilink.komma.common.AbstractKommaPlugin;
import net.enilink.komma.model.IModelSet;
import net.enilink.komma.model.MODELS;
import net.enilink.komma.core.InferencingCapability;
import net.enilink.komma.core.KommaException;
import net.enilink.komma.core.URI;
import net.enilink.komma.core.URIImpl;
import net.enilink.komma.sesame.SesameModule;

@Iri(MODELS.NAMESPACE + "OwlimModelSet")
public abstract class OWLIMModelSetSupport implements IModelSet.Internal {
	@Override
	public void collectInjectionModules(Collection<Module> modules) {
		modules.add(new SesameModule());
		modules.add(new AbstractModule() {
			@Override
			protected void configure() {
				bind(InferencingCapability.class).toInstance(new InferencingCapability() {
					@Override
					public boolean doesRDFS() {
						return true;
					}
					
					@Override
					public boolean doesOWL() {
						return true;
					}
				});
			}

			@SuppressWarnings("unused")
			@Singleton
			@Provides
			protected Repository provideRepository() {
				try {
					return createRepository();
				} catch (RepositoryException e) {
					throw new KommaException("Unable to create repository.", e);
				}
			}
		});
	}

	protected void addBasicKnowledge(Repository repository)
			throws RepositoryException {
		String[] bundles = { "net.enilink.vocab.owl",
				"net.enilink.vocab.rdfs" };

		if (AbstractKommaPlugin.IS_ECLIPSE_RUNNING) {
			RepositoryConnection conn = null;

			try {
				conn = repository.getConnection();
				for (String name : bundles) {
					URL url = FileLocator.find(Platform.getBundle(name),
							new Path("META-INF/org.openrdf.ontologies"),
							Collections.emptyMap());
					if (url != null) {
						URL resolvedUrl = FileLocator.resolve(url);

						Properties properties = new Properties();
						InputStream in = resolvedUrl.openStream();
						properties.load(in);
						in.close();

						URI baseUri = URIImpl.createURI(url.toString())
								.trimSegments(1);
						for (Map.Entry<Object, Object> entry : properties
								.entrySet()) {
							String file = entry.getKey().toString();
							if (file.contains("rdfs")) {
								// skip RDF and RDFS schema
								continue;
							}

							URIImpl fileUri = URIImpl.createFileURI(file);
							fileUri = fileUri.resolve(baseUri);

							resolvedUrl = FileLocator.resolve(new URL(fileUri
									.toString()));
							if (resolvedUrl != null) {
								in = resolvedUrl.openStream();
								if (in != null && in.available() > 0) {
									conn.add(in, "", RDFFormat.RDFXML);
								}
								if (in != null) {
									in.close();
								}
							}
						}
					}
				}
			} catch (IOException e) {
				throw new KommaException("Cannot access RDF data", e);
			} catch (RepositoryException e) {
				throw new KommaException("Loading RDF failed", e);
			} catch (RDFParseException e) {
				throw new KommaException("Invalid RDF data", e);
			} finally {
				if (conn != null) {
					try {
						conn.close();
					} catch (RepositoryException e) {
						KommaCore.log(e);
					}
				}
			}
		}
	}

	protected Repository createRepository() throws RepositoryException {
		try {
			RepositoryRegistry.getInstance().add(new SailRepositoryFactory());
			SailRegistry.getInstance().add(new OWLIMSailFactory());

			final Graph model = new GraphImpl();
			RDFParser parser = Rio.createParser(RDFFormat.TURTLE);
			parser.setRDFHandler(new StatementCollector(model));

			InputStream in = OWLIMModelSetSupport.class
					.getResourceAsStream("/resources/owlim.ttl");
			try {
				parser.parse(in, "http://example.org#");
			} finally {
				in.close();
			}
			Iterator<Statement> iter = model.match(null, RDF.TYPE,
					RepositoryConfigSchema.REPOSITORY);
			Resource repNode = null;
			if (iter.hasNext()) {
				Statement st = iter.next();
				repNode = st.getSubject();
			}
			RepositoryConfig repConfig = RepositoryConfig
					.create(model, repNode);

			File tmpDir = FileUtil.createTempDir("olwim");
			System.out.println("Using repository dir: "
					+ tmpDir.getAbsolutePath());

			RepositoryManager man = new LocalRepositoryManager(tmpDir);
			man.initialize();

			man.addRepositoryConfig(repConfig);

			Repository repository = man.getRepository("owlim");
			addBasicKnowledge(repository);
			return repository;
		} catch (Exception e) {
			throw new RepositoryException("Unable to initialize repository", e);
		}
	}

	@Override
	public boolean isPersistent() {
		return false;
	}
}
