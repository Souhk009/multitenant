package org.malagu.multitenant.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.persistence.EntityManagerFactory;
import javax.sql.DataSource;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.malagu.multitenant.Constants;
import org.malagu.multitenant.domain.Organization;
import org.malagu.multitenant.listener.EntityManagerFactoryCreateListener;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanClassLoaderAware;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.BeanNameAware;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.JpaProperties;
import org.springframework.boot.orm.jpa.EntityManagerFactoryBuilder;
import org.springframework.boot.orm.jpa.EntityManagerFactoryBuilder.Builder;
import org.springframework.boot.orm.jpa.hibernate.SpringJtaPlatform;
import org.springframework.context.ResourceLoaderAware;
import org.springframework.context.weaving.LoadTimeWeaverAware;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.core.io.ResourceLoader;
import org.springframework.instrument.classloading.LoadTimeWeaver;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jndi.JndiLocatorDelegate;
import org.springframework.orm.jpa.JpaVendorAdapter;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.persistenceunit.PersistenceUnitManager;
import org.springframework.orm.jpa.vendor.AbstractJpaVendorAdapter;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.jta.JtaTransactionManager;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;


/**
 * @author Kevin Yang (mailto:muxiangqiu@gmail.com)
 * @since 2017年11月24日
 */
@Service
public class EntityManagerFactoryServiceImpl implements
		EntityManagerFactoryService, BeanClassLoaderAware, BeanFactoryAware, BeanNameAware, ResourceLoaderAware, LoadTimeWeaverAware, InitializingBean {
	
	private Map<String, EntityManagerFactory> emfMap = new ConcurrentHashMap<String, EntityManagerFactory>();
	
	@Autowired
	private DataSourceService dataSourceService;
	
	@Autowired
	private ScriptService scriptService;
	
	@Value("${bdf3.multitenant.dataScript:}")
	private String dataScript;
	
	@Autowired
	private EntityManagerFactory emf;
	
	@Autowired(required = false)
	private JtaTransactionManager jtaTransactionManager;

	private LoadTimeWeaver loadTimeWeaver;

	private ResourceLoader resourceLoader;

	private ClassLoader classLoader;

	private String beanName;
	
	@Autowired(required = false)
	private List<EntityManagerFactoryCreateListener> listeners;
	
	private static final Log logger = LogFactory
			.getLog(HibernateJpaAutoConfiguration.class);

	private static final String JTA_PLATFORM = "hibernate.transaction.jta.platform";

	/**
	 * {@code NoJtaPlatform} implementations for various Hibernate versions.
	 */
	private static final String[] NO_JTA_PLATFORM_CLASSES = {
			"org.hibernate.engine.transaction.jta.platform.internal.NoJtaPlatform",
			"org.hibernate.service.jta.platform.internal.NoJtaPlatform" };

	/**
	 * {@code WebSphereExtendedJtaPlatform} implementations for various Hibernate
	 * versions.
	 */
	private static final String[] WEBSPHERE_JTA_PLATFORM_CLASSES = {
			"org.hibernate.engine.transaction.jta.platform.internal.WebSphereExtendedJtaPlatform",
			"org.hibernate.service.jta.platform.internal.WebSphereExtendedJtaPlatform", };

	@Autowired
	private JpaProperties properties;

	private BeanFactory beanFactory;
	
	@Autowired(required = false)
	private PersistenceUnitManager persistenceUnitManager;
	
	@Value("${bdf3.multitenant.packagesToScan:"
			+ "com.bstek.bdf3.security.orm,"
			+ "com.bstek.bdf3.notify.domain,"
			+ "com.bstek.bdf3.dictionary.domain,"
			+ "com.bstek.bdf3.log.model,"
			+ "com.bstek.bdf3.importer.model,"
			+ "com.bstek.bdf3.profile.domain}")
	private String packagesToScan;
	
	@Value("${bdf3.multitenant.customPackagesToScan:}")
	private String customPackagesToScan;
	
	protected AbstractJpaVendorAdapter createJpaVendorAdapter() {
		return new HibernateJpaVendorAdapter();
	}
	
	private String[] mergePackagesToScan() {
		String[] packages = null;
		if (StringUtils.hasText(packagesToScan) && StringUtils.hasText(customPackagesToScan)) {
			packages = (packagesToScan + "," + customPackagesToScan).split(",");
		} else if (StringUtils.hasText(packagesToScan)) {
			packages = packagesToScan.split(",");
		}  else if (StringUtils.hasText(customPackagesToScan)) {
			packages = customPackagesToScan.split(",");
		}
		return packages;
	}
	
	public JpaVendorAdapter getJpaVendorAdapter() {
		AbstractJpaVendorAdapter adapter = createJpaVendorAdapter();
		adapter.setShowSql(properties.isShowSql());
		adapter.setDatabase(properties.getDatabase());
		adapter.setDatabasePlatform(properties.getDatabasePlatform());
		adapter.setGenerateDdl(properties.isGenerateDdl());
		return adapter;
	}

	public EntityManagerFactoryBuilder getEntityManagerFactoryBuilder() {
		JpaVendorAdapter jpaVendorAdapter = getJpaVendorAdapter();
		EntityManagerFactoryBuilder builder = new EntityManagerFactoryBuilder(
				jpaVendorAdapter, properties.getProperties(),
				this.persistenceUnitManager);
		return builder;
	}

	@Override
	public EntityManagerFactory getEntityManagerFactory(Organization organization) {
		return emfMap.get(organization.getId());
	}

	@Override
	public EntityManagerFactory createEntityManagerFactory(Organization organization) {
		DataSource dataSource = dataSourceService.getOrCreateDataSource(organization);
		Map<String, Object> vendorProperties = getVendorProperties(dataSource);
		customizeVendorProperties(vendorProperties);
		LocalContainerEntityManagerFactoryBean entityManagerFactoryBean = getEntityManagerFactoryBuilder().dataSource(dataSource).packages(mergePackagesToScan())
				.properties(vendorProperties).jta(isJta()).build();
		entityManagerFactoryBean.setBeanClassLoader(classLoader);
		entityManagerFactoryBean.setBeanFactory(beanFactory);
		entityManagerFactoryBean.setBeanName(beanName);
		entityManagerFactoryBean.setLoadTimeWeaver(loadTimeWeaver);
		entityManagerFactoryBean.setResourceLoader(resourceLoader);
		entityManagerFactoryBean.setPersistenceUnitName(organization.getId());
		entityManagerFactoryBean.afterPropertiesSet();
		scriptService.runScripts(organization.getId(), dataSource, dataScript, "multitenant-data");
		return entityManagerFactoryBean.getObject();
	}
	
	protected Map<String, Object> getVendorProperties(DataSource dataSource) {
		Map<String, Object> vendorProperties = new LinkedHashMap<String, Object>();
		vendorProperties.putAll(this.properties.getHibernateProperties(dataSource));
		return vendorProperties;
	}

	protected void customizeVendorProperties(Map<String, Object> vendorProperties) {
		if (!vendorProperties.containsKey(JTA_PLATFORM)) {
			configureJtaPlatform(vendorProperties);
		}
	}

	private void configureJtaPlatform(Map<String, Object> vendorProperties)
			throws LinkageError {
		JtaTransactionManager jtaTransactionManager = getJtaTransactionManager();
		if (jtaTransactionManager != null) {
			if (runningOnWebSphere()) {
				configureWebSphereTransactionPlatform(vendorProperties);
			}
			else {
				configureSpringJtaPlatform(vendorProperties, jtaTransactionManager);
			}
		}
		else {
			vendorProperties.put(JTA_PLATFORM, getNoJtaPlatformManager());
		}
	}
	
	private boolean runningOnWebSphere() {
		return ClassUtils.isPresent(
				"com.ibm.websphere.jtaextensions." + "ExtendedJTATransaction",
				getClass().getClassLoader());
	}

	private void configureWebSphereTransactionPlatform(
			Map<String, Object> vendorProperties) {
		vendorProperties.put(JTA_PLATFORM, getWebSphereJtaPlatformManager());
	}

	private Object getWebSphereJtaPlatformManager() {
		return getJtaPlatformManager(WEBSPHERE_JTA_PLATFORM_CLASSES);
	}

	private void configureSpringJtaPlatform(Map<String, Object> vendorProperties,
			JtaTransactionManager jtaTransactionManager) {
		try {
			vendorProperties.put(JTA_PLATFORM,
					new SpringJtaPlatform(jtaTransactionManager));
		}
		catch (LinkageError ex) {		
			if (!isUsingJndi()) {
				throw new IllegalStateException("Unable to set Hibernate JTA "
						+ "platform, are you using the correct "
						+ "version of Hibernate?", ex);
			}
			if (logger.isDebugEnabled()) {
				logger.debug("Unable to set Hibernate JTA platform : " + ex.getMessage());
			}
		}
	}

	private boolean isUsingJndi() {
		try {
			return JndiLocatorDelegate.isDefaultJndiEnvironmentAvailable();
		}
		catch (Error ex) {
			return false;
		}
	}

	private Object getNoJtaPlatformManager() {
		return getJtaPlatformManager(NO_JTA_PLATFORM_CLASSES);
	}

	private Object getJtaPlatformManager(String[] candidates) {
		for (String candidate : candidates) {
			try {
				return Class.forName(candidate).newInstance();
			}
			catch (Exception ex) {
				// Continue searching
			}
		}
		throw new IllegalStateException("Could not configure JTA platform");
	}
	
	protected JtaTransactionManager getJtaTransactionManager() {
		return this.jtaTransactionManager;
	}

	protected final boolean isJta() {
		return (this.jtaTransactionManager != null);
	}

	@Override
	public void setBeanName(String name) {
		this.beanName = name;
		
	}

	@Override
	public void setBeanClassLoader(ClassLoader classLoader) {
		this.classLoader = classLoader;
		
	}
	
	@Override
	public void setLoadTimeWeaver(LoadTimeWeaver loadTimeWeaver) {
		this.loadTimeWeaver = loadTimeWeaver;
	}

	@Override
	public void setResourceLoader(ResourceLoader resourceLoader) {
		this.resourceLoader = resourceLoader;
	}

	@Override
	public void setBeanFactory(BeanFactory beanFactory) throws BeansException {
		this.beanFactory = beanFactory;
		
	}

	@Override
	public EntityManagerFactory getOrCreateEntityManagerFactory(
			Organization organization) {
		EntityManagerFactory emf = getEntityManagerFactory(organization);
		if (emf == null) {
			emf = createEntityManagerFactory(organization);
			emfMap.put(organization.getId(), emf);
		}
		return emf;
	}
	
	@Override
	public void generateTables(Organization organization) {
		SingleConnectionDataSource dataSource = dataSourceService.createSingleConnectionDataSource(organization);
		if (dataSource != null) {
			Map<String, Object> vendorProperties = getVendorProperties(dataSource);
			customizeVendorProperties(vendorProperties);
			Builder builder = getEntityManagerFactoryBuilder().dataSource(dataSource).packages(packagesToScan.split(","))
					.properties(vendorProperties).jta(isJta());
			LocalContainerEntityManagerFactoryBean entityManagerFactoryBean = builder.build();

		    publishEvent(organization, builder);

			entityManagerFactoryBean.setBeanClassLoader(classLoader);
			entityManagerFactoryBean.setBeanFactory(beanFactory);
			entityManagerFactoryBean.setBeanName(beanName);
			entityManagerFactoryBean.setLoadTimeWeaver(loadTimeWeaver);
			entityManagerFactoryBean.setResourceLoader(resourceLoader);
			entityManagerFactoryBean.afterPropertiesSet();
			entityManagerFactoryBean.destroy();
			dataSource.destroy();
		}
	}
	
	@Override
	public EntityManagerFactory createTempEntityManagerFactory(
			Organization organization) {
		SingleConnectionDataSource dataSource = dataSourceService.createSingleConnectionDataSource(organization);
		if (dataSource != null) {
			Map<String, Object> vendorProperties = getVendorProperties(dataSource);
			customizeVendorProperties(vendorProperties);
		    Builder builder = getEntityManagerFactoryBuilder().dataSource(dataSource).packages(mergePackagesToScan())
					.properties(vendorProperties).jta(isJta());
			LocalContainerEntityManagerFactoryBean entityManagerFactoryBean = builder.build();
			entityManagerFactoryBean.setBeanClassLoader(classLoader);
			entityManagerFactoryBean.setBeanFactory(beanFactory);
			entityManagerFactoryBean.setBeanName(beanName);
			entityManagerFactoryBean.setLoadTimeWeaver(loadTimeWeaver);
			entityManagerFactoryBean.setResourceLoader(resourceLoader);
			entityManagerFactoryBean.afterPropertiesSet();
			return entityManagerFactoryBean.getObject();
		}
		return null;
	}

	@Override
	public void afterPropertiesSet() throws Exception {
		emfMap.put(Constants.MASTER, emf);
		if (listeners != null) {
			AnnotationAwareOrderComparator.sort(listeners);
		}
	}

	@Override
	public void removeEntityManagerFactory(Organization organization) {
		emfMap.remove(organization.getId());
		dataSourceService.removeDataSource(organization);
	}
	
	private void publishEvent(Organization organization, Builder builder) {
		if (listeners != null) {
			for (EntityManagerFactoryCreateListener entityManagerFactoryCreateListener : listeners) {
				entityManagerFactoryCreateListener.onCreate(organization, builder);
			}
		}
		
	}

}
