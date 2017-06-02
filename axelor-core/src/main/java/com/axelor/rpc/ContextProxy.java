/**
 * Axelor Business Solutions
 *
 * Copyright (C) 2005-2017 Axelor (<http://axelor.com>).
 *
 * This program is free software: you can redistribute it and/or  modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.axelor.rpc;

import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

import com.axelor.auth.db.AuditableModel;
import com.axelor.db.JPA;
import com.axelor.db.Model;
import com.axelor.db.mapper.Mapper;
import com.axelor.db.mapper.Property;
import com.axelor.meta.db.MetaJsonRecord;

import net.bytebuddy.ByteBuddy;
import net.bytebuddy.dynamic.DynamicType.Builder;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import net.bytebuddy.implementation.InvocationHandlerAdapter;
import net.bytebuddy.implementation.MethodCall;
import net.bytebuddy.implementation.MethodDelegation;
import net.bytebuddy.implementation.bind.annotation.AllArguments;
import net.bytebuddy.implementation.bind.annotation.Origin;
import net.bytebuddy.implementation.bind.annotation.RuntimeType;
import net.bytebuddy.implementation.bind.annotation.SuperCall;
import net.bytebuddy.matcher.ElementMatchers;

/**
 * This {@link ContextProxy} provides seamless way to access context values
 * using proxy.
 * 
 * <p>
 * The proxy and it's fields are initialized lazily from the context value map
 * when context variable is access. Any missing value of the bean is accessed
 * from the managed instance.
 * 
 * <p>
 * For internal use only.
 * 
 * @see Context
 */
public class ContextProxy<T> {

	private static final String FIELD_ID = "id";
	private static final String FIELD_VERSION = "version";
	private static final String FIELD_SELECTED = "selected";
	
	private static final String COMPUTE_PREFIX = "compute";
	
	private final PropertyChangeSupport changeListeners;

	private final Map<String, Object> values;
	private final Set<String> validated;

	private final Class<T> beanClass;
	private final Mapper beanMapper;

	private Object managed;
	private Object unmanaged;
	
	private T proxied;

	private boolean searched;

	private ContextProxy(Class<T> beanClass, Map<String, Object> values) {
		this.values = Objects.requireNonNull(values);
		this.validated = new HashSet<>();
		this.beanClass = Objects.requireNonNull(beanClass);
		this.beanMapper = Mapper.of(beanClass);
		this.changeListeners = new PropertyChangeSupport(this);
	}
	
	public void addChangeListener(PropertyChangeListener listener) {
		changeListeners.addPropertyChangeListener(listener);
	}

	private Long findId(Map<String, Object> values) {
		try {
			return Long.parseLong(values.get(FIELD_ID).toString());
		} catch (Exception e) {
			return null;
		}
	}

	private Object managed() {
		if (searched) {
			return managed;
		}
		final Long id = findId(values);
		if (id != null) {
			managed = (Model) JPA.em().find(beanClass, id);
		}
		searched = true;
		return managed;
	}

	private Object unmanaged() {
		if (unmanaged == null) {
			unmanaged = Mapper.toBean(beanClass, null);
		}
		return unmanaged;
	}

	private Object populated() {
		final Object bean = unmanaged();
		final Object managed = managed();

		// populate the bean
		for (Property property : beanMapper.getProperties()) {
			this.validate(property);
			if (property.isVirtual() && managed != null) {
				final Set<String> depends = beanMapper.getComputeDependencies(property);
				if (depends != null && !depends.isEmpty()) {
					depends.stream()
						.filter(n -> !validated.contains(n))
						.forEach(n -> beanMapper.set(bean, n, beanMapper.get(managed, n)));
				}
			}
		}

		// make sure to have version value
		if (bean instanceof Model && !values.containsKey(FIELD_VERSION)) {
			if (managed != null) {
				((Model) bean).setVersion(((Model) managed).getVersion());
			}
		}
		return bean;
	}

	@SuppressWarnings("unchecked")
	private Object createOrFind(Property property, Object item) {
		if (item == null || item instanceof Model) {
			return item;
		}
		if (item instanceof Map) {
			final Map<String, Object> map = (Map<String, Object>) item;
			final Long id = findId(map);
			// if new or updated, create proxy
			if (id == null || id <= 0 || map.containsKey(FIELD_VERSION)) {
				return of(property.getTarget(), map).get();
			}
			// use managed instance
			final Object bean = JPA.em().find(property.getTarget(), id);
			if (map.containsKey(FIELD_SELECTED)) {
				Mapper.of(property.getTarget()).set(bean, FIELD_SELECTED, map.get(FIELD_SELECTED));
			}
			return bean;
		}
		if (item instanceof Number) {
			return JPA.em().find(property.getTarget(), item);
		}
		throw new IllegalArgumentException("Invalid collection item for field: " + property.getName());
	}

	private void validate(Property property) {
		if (property == null
				|| validated.contains(property.getName())
				|| !values.containsKey(property.getName())) {
			return;
		}
		Object value = values.get(property.getName());
		if (property.isCollection() && value instanceof Collection) {
			value = ((Collection<?>) value).stream()
					.map(item -> createOrFind(property, item))
					.collect(Collectors.toList());
		} else if (property.isReference()) {
			value = createOrFind(property, value);
		}

		final Object bean = unmanaged();

		Mapper mapper = beanMapper;
		if (mapper.getSetter(property.getName()) == null && bean instanceof AuditableModel) {
			mapper = Mapper.of(AuditableModel.class);
		}

		// prevent automatic association handling
		// causing detached entity exception
		mapper.set(bean, property.getName(), value);

		validated.add(property.getName());
	}
	
	private Object interceptCompute(Callable<?> superCall, Method method, Object[] args) throws Exception {
		final Property computed = beanMapper.getProperty(method);
		final Set<String> depends;
		if (computed == null || (depends = beanMapper.getComputeDependencies(computed)) == null || depends.isEmpty()) {
			return superCall.call();
		}

		for (String name : depends) {
			final Property property;
			if (validated.contains(name) || (property = beanMapper.getProperty(name)) == null) {
				continue;
			}
			if (values.containsKey(name)) {
				validate(property);
			} else {
				beanMapper.set(unmanaged(), name, property.get(managed()));
			}
		}

		method.setAccessible(true);
		return method.invoke(unmanaged(), args);
	}

	@RuntimeType
	public Object intercept(@SuperCall Callable<?> superCall, @Origin Method method, @AllArguments Object[] args) throws Throwable {

		// handle compute method calls
		if (Modifier.isProtected(method.getModifiers())) {
			return interceptCompute(superCall, method, args);
		}

		final Property property = beanMapper.getProperty(method);
		// no fields defined or is computed field
		if (property == null || property.isVirtual()) {
			return superCall.call();
		}

		final String fieldName = property.getName();

		// in case of setter, update context map
		final Object oldValue = args.length == 1
				? values.put(fieldName, args[0])
				: null;
	
		// if setter or value found in context map for the getter
		if (args.length == 1 || values.containsKey(fieldName) || property.isTransient()) {
			validate(property);
			try {
				return method.invoke(this.unmanaged(), args);
			} finally {
				if (args.length == 1 && changeListeners.hasListeners(fieldName)) {
					changeListeners.firePropertyChange(fieldName, oldValue, values.get(fieldName));
				}
			}
		}
		// else get value from managed instance
		final Object managed = this.managed();
		if (managed == null) {
			return null;
		}

		return method.invoke(managed, args);
	}

	public T get() {
		return proxied;
	}

	private boolean hasJsonFields() {
		final Property attrs = beanMapper.getProperty(Context.KEY_JSON_ATTRS);
		return attrs != null && attrs.isJson();
	}

	private JsonContext createJsonContext() {
		if (MetaJsonRecord.class.isAssignableFrom(beanClass)) {
			final MetaJsonRecord rec = (MetaJsonRecord) proxied;
			return new JsonContext(rec);
		}
		final Property p = beanMapper.getProperty(Context.KEY_JSON_ATTRS);
		final Context c = new Context(beanClass);
		return new JsonContext(c, p, (String) p.get(proxied));
	}

	@SuppressWarnings("unchecked")
	public static <T> ContextProxy<T> of(final Class<T> beanClass, final Map<String, Object> values) {
		Objects.requireNonNull(values, "values map cannot be null");
		final ContextProxy<T> proxy = new ContextProxy<>(beanClass, values);
		
		Builder<T> builder = new ByteBuddy()
				.subclass(beanClass)
				.method(ElementMatchers.isPublic().and(ElementMatchers.isGetter().or(ElementMatchers.isSetter())))
				.intercept(MethodDelegation.to(proxy))
				.method(ElementMatchers.isProtected().and(ElementMatchers.nameStartsWith(COMPUTE_PREFIX)))
				.intercept(MethodDelegation.to(proxy))
				.implement(ContextEntity.class)
				.method(ElementMatchers.isDeclaredBy(ContextEntity.class))
				.intercept(MethodCall.call(proxy::populated));

		// allow to seamlessly handle json field values from scripts
		if (proxy.hasJsonFields()) {
			final Callable<JsonContext> context = new Callable<JsonContext>() {
				JsonContext ctx;
				@Override
				public JsonContext call() throws Exception {
					if (ctx == null) {
						ctx = proxy.createJsonContext();
					}
					return ctx;
				}
			};
			builder = builder
					.implement(Map.class)
					.method(ElementMatchers.isDeclaredBy(Map.class))
					.intercept(InvocationHandlerAdapter.of((p, method, args) -> {
						switch (method.getName()) {
						case "get":
						case "put":
							final String name = (String) args[0];
							final Method found = args.length == 2
									? proxy.beanMapper.getSetter(name)
									: proxy.beanMapper.getGetter(name);
							if (found == null) {
								return method.invoke(context.call(), args);
							}
							final Object[] params = args.length == 2
									? new Object[]{ args[1] }
									: new Object[]{};
							return found.invoke(proxy.proxied, params);
						}
						throw new UnsupportedOperationException("cannot call '" + method + "' on proxy object");
					}));
		}

		final Class<?> proxyClass = builder.make()
			.load(beanClass.getClassLoader(), ClassLoadingStrategy.Default.WRAPPER)
			.getLoaded();

		try {
			proxy.proxied = (T) proxyClass.newInstance();
		} catch (InstantiationException | IllegalAccessException e) {
			throw new RuntimeException(e);
		}

		return proxy;
	}
}