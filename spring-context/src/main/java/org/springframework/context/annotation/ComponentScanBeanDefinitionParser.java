/*
 * Copyright 2002-2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.context.annotation;

import java.lang.annotation.Annotation;
import java.util.Set;
import java.util.regex.Pattern;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.beans.factory.parsing.BeanComponentDefinition;
import org.springframework.beans.factory.parsing.CompositeComponentDefinition;
import org.springframework.beans.factory.support.BeanNameGenerator;
import org.springframework.beans.factory.xml.BeanDefinitionParser;
import org.springframework.beans.factory.xml.ParserContext;
import org.springframework.beans.factory.xml.XmlReaderContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.core.type.filter.AspectJTypeFilter;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.core.type.filter.RegexPatternTypeFilter;
import org.springframework.core.type.filter.TypeFilter;
import org.springframework.lang.Nullable;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StringUtils;

/**
 * Parser for the {@code <context:component-scan/>} element.
 *
 * @author Mark Fisher
 * @author Ramnivas Laddad
 * @author Juergen Hoeller
 * @since 2.5
 */
public class ComponentScanBeanDefinitionParser implements BeanDefinitionParser {

	private static final String BASE_PACKAGE_ATTRIBUTE = "base-package";

	private static final String RESOURCE_PATTERN_ATTRIBUTE = "resource-pattern";

	private static final String USE_DEFAULT_FILTERS_ATTRIBUTE = "use-default-filters";

	private static final String ANNOTATION_CONFIG_ATTRIBUTE = "annotation-config";

	private static final String NAME_GENERATOR_ATTRIBUTE = "name-generator";

	private static final String SCOPE_RESOLVER_ATTRIBUTE = "scope-resolver";

	private static final String SCOPED_PROXY_ATTRIBUTE = "scoped-proxy";

	private static final String EXCLUDE_FILTER_ELEMENT = "exclude-filter";

	private static final String INCLUDE_FILTER_ELEMENT = "include-filter";

	private static final String FILTER_TYPE_ATTRIBUTE = "type";

	private static final String FILTER_EXPRESSION_ATTRIBUTE = "expression";


	@Override
	@Nullable
	public BeanDefinition parse(Element element, ParserContext parserContext) {
		// 1. 拿到<context:component-scan> 节点的base-package属性值
		String basePackage = element.getAttribute(BASE_PACKAGE_ATTRIBUTE);
		// 2. 解析占位符，例如${basePackage}
		basePackage = parserContext.getReaderContext().getEnvironment().resolvePlaceholders(basePackage);
		// 3. 解析base-package（允许通过",; \t\n" 中的任一符号填写多个），例如com.test.open;com.test2.open
		String[] basePackages = StringUtils.tokenizeToStringArray(basePackage,
				ConfigurableApplicationContext.CONFIG_LOCATION_DELIMITERS);

		// Actually scan for bean definitions and register them.
		// 4. 构建和配置ClassPathBeanDefinitionScanner
		ClassPathBeanDefinitionScanner scanner = configureScanner(parserContext, element);
		// 5. 使用scanner在指定的basePackage包中执行扫描，返回已注册的bean的定义
		Set<BeanDefinitionHolder> beanDefinitions = scanner.doScan(basePackages);
		// 6. 组件注册（包括注册一些内部的注解后置处理器、触发注册事件）
		registerComponents(parserContext.getReaderContext(), beanDefinitions, element);

		return null;
	}

	protected ClassPathBeanDefinitionScanner configureScanner(ParserContext parserContext, Element element) {
		// 1. 解析use-default-filters属性，默认为true，用于指示是否使用默认的filter
		boolean useDefaultFilters = true;
		if (element.hasAttribute(USE_DEFAULT_FILTERS_ATTRIBUTE)) {
			useDefaultFilters = Boolean.parseBoolean(element.getAttribute(USE_DEFAULT_FILTERS_ATTRIBUTE));
		}

		// Delegate bean definition registration to scanner class.
		// 2. 构建ClassPathDefinitionScanner，将bean定义注册委托给scanner
		ClassPathBeanDefinitionScanner scanner = createScanner(parserContext.getReaderContext(), useDefaultFilters);
		scanner.setBeanDefinitionDefaults(parserContext.getDelegate().getBeanDefinitionDefaults());
		scanner.setAutowireCandidatePatterns(parserContext.getDelegate().getAutowireCandidatePatterns());

		// 3. 解析resource-pattern
		if (element.hasAttribute(RESOURCE_PATTERN_ATTRIBUTE)) {
			scanner.setResourcePattern(element.getAttribute(RESOURCE_PATTERN_ATTRIBUTE));
		}

		try {
			// 4. 解析resource-generator属性
			parseBeanNameGenerator(element, scanner);
		}
		catch (Exception ex) {
			parserContext.getReaderContext().error(ex.getMessage(), parserContext.extractSource(element), ex.getCause());
		}

		try {
			// 5. 解析scope-resolve、scoped-proxy属性
			parseScope(element, scanner);
		}
		catch (Exception ex) {
			parserContext.getReaderContext().error(ex.getMessage(), parserContext.extractSource(element), ex.getCause());
		}

		// 6. 解析类型过滤器
		parseTypeFilters(element, scanner, parserContext);

		return scanner;
	}

	protected ClassPathBeanDefinitionScanner createScanner(XmlReaderContext readerContext, boolean useDefaultFilters) {
		return new ClassPathBeanDefinitionScanner(readerContext.getRegistry(), useDefaultFilters,
				readerContext.getEnvironment(), readerContext.getResourceLoader());
	}

	protected void registerComponents(
			XmlReaderContext readerContext, Set<BeanDefinitionHolder> beanDefinitions, Element element) {

		Object source = readerContext.extractSource(element);
		// 1. 使用注解的tagName（例如：context：component-scan）和source构建CompositeComponentDefinition
		CompositeComponentDefinition compositeDef = new CompositeComponentDefinition(element.getTagName(), source);

		// 2. 将扫描到的所有BeanDefinition添加到compositeDef的nestedComponents
		for (BeanDefinitionHolder beanDefHolder : beanDefinitions) {
			compositeDef.addNestedComponent(new BeanComponentDefinition(beanDefHolder));
		}

		// Register annotation config processors, if necessary.
		boolean annotationConfig = true;
		// 3. 获取component-scan标签的annotation-config属性值（默认为true）
		if (element.hasAttribute(ANNOTATION_CONFIG_ATTRIBUTE)) {
			annotationConfig = Boolean.parseBoolean(element.getAttribute(ANNOTATION_CONFIG_ATTRIBUTE));
		}
		// 4. 如果annotation-config属性值为true，在给定的注册表中注册所有用于注解的Bean后置处理器
		if (annotationConfig) {
			Set<BeanDefinitionHolder> processorDefinitions =
					AnnotationConfigUtils.registerAnnotationConfigProcessors(readerContext.getRegistry(), source);
			// 5. 将注册的注解后置处理器的BeanDefinition添加到compositeDef的nestedComponents属性中
			for (BeanDefinitionHolder processorDefinition : processorDefinitions) {
				compositeDef.addNestedComponent(new BeanComponentDefinition(processorDefinition));
			}
		}

		// 6. 触发组件注册事件，默认实现为EmptyReaderEventListener
		readerContext.fireComponentRegistered(compositeDef);
	}

	protected void parseBeanNameGenerator(Element element, ClassPathBeanDefinitionScanner scanner) {
		if (element.hasAttribute(NAME_GENERATOR_ATTRIBUTE)) {
			BeanNameGenerator beanNameGenerator = (BeanNameGenerator) instantiateUserDefinedStrategy(
					element.getAttribute(NAME_GENERATOR_ATTRIBUTE), BeanNameGenerator.class,
					scanner.getResourceLoader().getClassLoader());
			scanner.setBeanNameGenerator(beanNameGenerator);
		}
	}

	protected void parseScope(Element element, ClassPathBeanDefinitionScanner scanner) {
		// Register ScopeMetadataResolver if class name provided.
		if (element.hasAttribute(SCOPE_RESOLVER_ATTRIBUTE)) {
			if (element.hasAttribute(SCOPED_PROXY_ATTRIBUTE)) {
				throw new IllegalArgumentException(
						"Cannot define both 'scope-resolver' and 'scoped-proxy' on <component-scan> tag");
			}
			ScopeMetadataResolver scopeMetadataResolver = (ScopeMetadataResolver) instantiateUserDefinedStrategy(
					element.getAttribute(SCOPE_RESOLVER_ATTRIBUTE), ScopeMetadataResolver.class,
					scanner.getResourceLoader().getClassLoader());
			scanner.setScopeMetadataResolver(scopeMetadataResolver);
		}

		if (element.hasAttribute(SCOPED_PROXY_ATTRIBUTE)) {
			String mode = element.getAttribute(SCOPED_PROXY_ATTRIBUTE);
			if ("targetClass".equals(mode)) {
				scanner.setScopedProxyMode(ScopedProxyMode.TARGET_CLASS);
			}
			else if ("interfaces".equals(mode)) {
				scanner.setScopedProxyMode(ScopedProxyMode.INTERFACES);
			}
			else if ("no".equals(mode)) {
				scanner.setScopedProxyMode(ScopedProxyMode.NO);
			}
			else {
				throw new IllegalArgumentException("scoped-proxy only supports 'no', 'interfaces' and 'targetClass'");
			}
		}
	}

	protected void parseTypeFilters(Element element, ClassPathBeanDefinitionScanner scanner, ParserContext parserContext) {
		// Parse exclude and include filter elements.
		ClassLoader classLoader = scanner.getResourceLoader().getClassLoader();
		NodeList nodeList = element.getChildNodes();
		// 1. 遍历解析element下的所有子节点
		for (int i = 0; i < nodeList.getLength(); i++) {
			Node node = nodeList.item(i);
			if (node.getNodeType() == Node.ELEMENT_NODE) {
				// 拿到节点的localName，例如节点：<context:exclude-filter type="" expression=""/> ，localName为exclude-filter
				String localName = parserContext.getDelegate().getLocalName(node);
				try {
					/**
					 * 例如
					 * <context:component-scan base-package="com.joonwhee.open">
					 *     <context:exclude-filter type="annotation" expression="org.springframework.stereotype.Controller"/>
					 * </context:component-scan>
					 */
					// 2.解析include-filter子节
					if (INCLUDE_FILTER_ELEMENT.equals(localName)) {
						// 2.1 构建TypeFilter
						TypeFilter typeFilter = createTypeFilter((Element) node, classLoader, parserContext);
						// 2.1 构建TypeFilter
						scanner.addIncludeFilter(typeFilter);
					}
					// 3.解析exclude-filter子节点
					else if (EXCLUDE_FILTER_ELEMENT.equals(localName)) {
						// 3.1 构建TypeFilter
						TypeFilter typeFilter = createTypeFilter((Element) node, classLoader, parserContext);
						// 3.2 添加到scanner的excludeFilters属性
						scanner.addExcludeFilter(typeFilter);
					}
				}
				catch (ClassNotFoundException ex) {
					parserContext.getReaderContext().warning(
							"Ignoring non-present type filter class: " + ex, parserContext.extractSource(element));
				}
				catch (Exception ex) {
					parserContext.getReaderContext().error(
							ex.getMessage(), parserContext.extractSource(element), ex.getCause());
				}
			}
		}
	}

	@SuppressWarnings("unchecked")
	protected TypeFilter createTypeFilter(Element element, @Nullable ClassLoader classLoader,
			ParserContext parserContext) throws ClassNotFoundException {

		// 1. 获取type、expression
		String filterType = element.getAttribute(FILTER_TYPE_ATTRIBUTE);
		String expression = element.getAttribute(FILTER_EXPRESSION_ATTRIBUTE);
		expression = parserContext.getReaderContext().getEnvironment().resolvePlaceholders(expression);
		// 2. 根据filterType，返回对应的TypeFilter，例如annotation返回AnnotationTypeFilter
		// 2.1 指定过滤的注解, expression为注解的类全名称, 例如: org.springframework.stereotype.Controller
		if ("annotation".equals(filterType)) {
			return new AnnotationTypeFilter((Class<Annotation>) ClassUtils.forName(expression, classLoader));
		}
		// 2.2 指定过滤的类或接口, 包括子类和子接口, expression为类全名称
		else if ("assignable".equals(filterType)) {
			return new AssignableTypeFilter(ClassUtils.forName(expression, classLoader));
		}
		// 2.3 指定aspectj表达式来过滤类, expression为aspectj表达式字符串
		else if ("aspectj".equals(filterType)) {
			return new AspectJTypeFilter(expression, classLoader);
		}
		// 2.4 通过正则表达式来过滤类, expression为正则表达式字符串
		else if ("regex".equals(filterType)) {
			return new RegexPatternTypeFilter(Pattern.compile(expression));
		}
		// 2.5 用户自定义过滤器类型, expression为自定义过滤器的类全名称
		else if ("custom".equals(filterType)) {
			Class<?> filterClass = ClassUtils.forName(expression, classLoader);
			// 2.5 用户自定义过滤器类型, expression为自定义过滤器的类全名称
			if (!TypeFilter.class.isAssignableFrom(filterClass)) {
				throw new IllegalArgumentException(
						"Class is not assignable to [" + TypeFilter.class.getName() + "]: " + expression);
			}
			return (TypeFilter) BeanUtils.instantiateClass(filterClass);
		}
		else {
			throw new IllegalArgumentException("Unsupported filter type: " + filterType);
		}
	}

	@SuppressWarnings("unchecked")
	private Object instantiateUserDefinedStrategy(
			String className, Class<?> strategyType, @Nullable ClassLoader classLoader) {

		Object result;
		try {
			result = ReflectionUtils.accessibleConstructor(ClassUtils.forName(className, classLoader)).newInstance();
		}
		catch (ClassNotFoundException ex) {
			throw new IllegalArgumentException("Class [" + className + "] for strategy [" +
					strategyType.getName() + "] not found", ex);
		}
		catch (Throwable ex) {
			throw new IllegalArgumentException("Unable to instantiate class [" + className + "] for strategy [" +
					strategyType.getName() + "]: a zero-argument constructor is required", ex);
		}

		if (!strategyType.isAssignableFrom(result.getClass())) {
			throw new IllegalArgumentException("Provided class name must be an implementation of " + strategyType);
		}
		return result;
	}

}
