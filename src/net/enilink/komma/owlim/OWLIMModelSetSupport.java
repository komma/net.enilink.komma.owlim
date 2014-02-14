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
import java.io.InputStream;
import java.util.Collection;
import java.util.Iterator;

import net.enilink.composition.annotations.Iri;
import net.enilink.komma.core.EntityVar;
import net.enilink.komma.core.InferencingCapability;
import net.enilink.komma.model.MODELS;
import net.enilink.komma.model.sesame.MemoryModelSetSupport;

import org.openrdf.model.Model;
import org.openrdf.model.Resource;
import org.openrdf.model.Statement;
import org.openrdf.model.impl.LinkedHashModel;
import org.openrdf.model.vocabulary.RDF;
import org.openrdf.repository.Repository;
import org.openrdf.repository.RepositoryException;
import org.openrdf.repository.config.RepositoryConfig;
import org.openrdf.repository.config.RepositoryConfigSchema;
import org.openrdf.repository.config.RepositoryRegistry;
import org.openrdf.repository.manager.LocalRepositoryManager;
import org.openrdf.repository.manager.RepositoryManager;
import org.openrdf.repository.sail.config.SailRepositoryFactory;
import org.openrdf.rio.RDFFormat;
import org.openrdf.rio.RDFParser;
import org.openrdf.rio.Rio;
import org.openrdf.rio.helpers.StatementCollector;
import org.openrdf.sail.config.SailRegistry;

import com.google.inject.AbstractModule;
import com.google.inject.Module;
import com.ontotext.trree.owlim_ext.config.OWLIMSailFactory;

@Iri(MODELS.NAMESPACE + "OwlimModelSet")
public abstract class OWLIMModelSetSupport extends MemoryModelSetSupport {
	EntityVar<RepositoryManager> repositoryManager;

	@Override
	public void collectInjectionModules(Collection<Module> modules) {
		super.collectInjectionModules(modules);
		modules.add(new AbstractModule() {
			@Override
			protected void configure() {
				bind(InferencingCapability.class).toInstance(
						new InferencingCapability() {
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
		});
	}

	protected Repository createRepository() throws RepositoryException {
		try {
			RepositoryRegistry.getInstance().add(new SailRepositoryFactory());
			SailRegistry.getInstance().add(new OWLIMSailFactory());

			final Model model = new LinkedHashModel();
			RDFParser parser = Rio.createParser(RDFFormat.TURTLE);
			parser.setRDFHandler(new StatementCollector(model));

			InputStream in = OWLIMModelSetSupport.class
					.getResourceAsStream("/resources/owlim-lite.ttl");
			try {
				parser.parse(in, "http://example.org#");
			} finally {
				in.close();
			}
			Iterator<Statement> iter = model.filter(null, RDF.TYPE,
					RepositoryConfigSchema.REPOSITORY).iterator();
			Resource repNode = null;
			if (iter.hasNext()) {
				Statement st = iter.next();
				repNode = st.getSubject();
			}
			RepositoryConfig repConfig = RepositoryConfig
					.create(model, repNode);

			File tmpDir = FileUtil.createTempDir("olwim");
			tmpDir.deleteOnExit();
			System.out.println("Using repository dir: "
					+ tmpDir.getAbsolutePath());

			RepositoryManager man = new LocalRepositoryManager(tmpDir);
			man.initialize();
			man.addRepositoryConfig(repConfig);

			Repository repository = man.getRepository("owlim");
			addBasicKnowledge(repository);
			repositoryManager.set(man);
			return repository;
		} catch (Exception e) {
			throw new RepositoryException("Unable to initialize repository", e);
		}
	}

	@Override
	public void dispose() {
		// close repository manager to free all managed repositories
		RepositoryManager man = repositoryManager.get();
		if (man != null) {
			man.shutDown();
		}
		repositoryManager.remove();
	}
}
