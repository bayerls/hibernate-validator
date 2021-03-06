/*
 * Hibernate Validator, declare and validate application constraints
 *
 * License: Apache License, Version 2.0
 * See the license.txt file in the root directory or <http://www.apache.org/licenses/LICENSE-2.0>.
 */
package org.hibernate.validator.internal.xml;

import static org.hibernate.validator.internal.util.CollectionHelper.newArrayList;
import static org.hibernate.validator.internal.util.CollectionHelper.newHashMap;
import static org.hibernate.validator.internal.util.CollectionHelper.newHashSet;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.validation.ValidationException;

import org.hibernate.validator.internal.metadata.cascading.CascadingTypeParameter;
import org.hibernate.validator.internal.metadata.core.AnnotationProcessingOptionsImpl;
import org.hibernate.validator.internal.metadata.core.MetaConstraint;
import org.hibernate.validator.internal.metadata.descriptor.ConstraintDescriptorImpl;
import org.hibernate.validator.internal.metadata.location.ConstraintLocation;
import org.hibernate.validator.internal.metadata.raw.ConfigurationSource;
import org.hibernate.validator.internal.metadata.raw.ConstrainedExecutable;
import org.hibernate.validator.internal.metadata.raw.ConstrainedParameter;
import org.hibernate.validator.internal.util.ExecutableHelper;
import org.hibernate.validator.internal.util.ReflectionHelper;
import org.hibernate.validator.internal.util.logging.Log;
import org.hibernate.validator.internal.util.logging.LoggerFactory;
import org.hibernate.validator.internal.util.privilegedactions.GetDeclaredConstructor;
import org.hibernate.validator.internal.util.privilegedactions.GetDeclaredMethod;
import org.hibernate.validator.internal.xml.binding.ConstraintType;
import org.hibernate.validator.internal.xml.binding.ConstructorType;
import org.hibernate.validator.internal.xml.binding.CrossParameterType;
import org.hibernate.validator.internal.xml.binding.MethodType;
import org.hibernate.validator.internal.xml.binding.ParameterType;
import org.hibernate.validator.internal.xml.binding.ReturnValueType;

/**
 * Builder for constrained methods and constructors.
 *
 * @author Hardy Ferentschik
 */
class ConstrainedExecutableBuilder {

	private static final Log log = LoggerFactory.make();

	private final ClassLoadingHelper classLoadingHelper;
	private final MetaConstraintBuilder metaConstraintBuilder;
	private final GroupConversionBuilder groupConversionBuilder;
	private final ConstrainedParameterBuilder constrainedParameterBuilder;
	private final AnnotationProcessingOptionsImpl annotationProcessingOptions;

	ConstrainedExecutableBuilder(ClassLoadingHelper classLoadingHelper, MetaConstraintBuilder metaConstraintBuilder,
			GroupConversionBuilder groupConversionBuilder, AnnotationProcessingOptionsImpl annotationProcessingOptions) {
		this.classLoadingHelper = classLoadingHelper;
		this.metaConstraintBuilder = metaConstraintBuilder;
		this.groupConversionBuilder = groupConversionBuilder;
		this.constrainedParameterBuilder = new ConstrainedParameterBuilder(
				metaConstraintBuilder,
				groupConversionBuilder,
				annotationProcessingOptions
		);
		this.annotationProcessingOptions = annotationProcessingOptions;
	}

	Set<ConstrainedExecutable> buildMethodConstrainedExecutable(List<MethodType> methods,
																			  Class<?> beanClass,
																			  String defaultPackage) {
		Set<ConstrainedExecutable> constrainedExecutables = newHashSet();
		List<Method> alreadyProcessedMethods = newArrayList();
		for ( MethodType methodType : methods ) {
			// parse the parameters
			List<Class<?>> parameterTypes = createParameterTypes(
					methodType.getParameter(),
					beanClass,
					defaultPackage
			);

			String methodName = methodType.getName();

			final Method method = run(
					GetDeclaredMethod.action(
							beanClass,
							methodName,
							parameterTypes.toArray( new Class[parameterTypes.size()] )
					)
			);

			if ( method == null ) {
				throw log.getBeanDoesNotContainMethodException(
						beanClass,
						methodName,
						parameterTypes
				);
			}

			if ( alreadyProcessedMethods.contains( method ) ) {
				throw log.getMethodIsDefinedTwiceInMappingXmlForBeanException( method, beanClass );
			}
			else {
				alreadyProcessedMethods.add( method );
			}

			// ignore annotations
			if ( methodType.getIgnoreAnnotations() != null ) {
				annotationProcessingOptions.ignoreConstraintAnnotationsOnMember(
						method,
						methodType.getIgnoreAnnotations()
				);
			}

			ConstrainedExecutable constrainedExecutable = parseExecutableType(
					defaultPackage,
					methodType.getParameter(),
					methodType.getCrossParameter(),
					methodType.getReturnValue(),
					method
			);

			constrainedExecutables.add( constrainedExecutable );
		}
		return constrainedExecutables;
	}

	Set<ConstrainedExecutable> buildConstructorConstrainedExecutable(List<ConstructorType> constructors,
																				   Class<?> beanClass,
																				   String defaultPackage) {
		Set<ConstrainedExecutable> constrainedExecutables = newHashSet();
		List<Constructor<?>> alreadyProcessedConstructors = newArrayList();
		for ( ConstructorType constructorType : constructors ) {
			// parse the parameters
			List<Class<?>> constructorParameterTypes = createParameterTypes(
					constructorType.getParameter(),
					beanClass,
					defaultPackage
			);

			final Constructor<?> constructor = run(
					GetDeclaredConstructor.action(
							beanClass,
							constructorParameterTypes.toArray( new Class[constructorParameterTypes.size()] )
					)
			);

			if ( constructor == null ) {
				throw log.getBeanDoesNotContainConstructorException(
						beanClass,
						constructorParameterTypes
				);
			}
			if ( alreadyProcessedConstructors.contains( constructor ) ) {
				throw log.getConstructorIsDefinedTwiceInMappingXmlForBeanException(
						constructor,
						beanClass
				);
			}
			else {
				alreadyProcessedConstructors.add( constructor );
			}

			// ignore annotations
			if ( constructorType.getIgnoreAnnotations() != null ) {
				annotationProcessingOptions.ignoreConstraintAnnotationsOnMember(
						constructor,
						constructorType.getIgnoreAnnotations()
				);
			}

			ConstrainedExecutable constrainedExecutable = parseExecutableType(
					defaultPackage,
					constructorType.getParameter(),
					constructorType.getCrossParameter(),
					constructorType.getReturnValue(),
					constructor
			);
			constrainedExecutables.add( constrainedExecutable );
		}
		return constrainedExecutables;
	}

	private ConstrainedExecutable parseExecutableType(String defaultPackage,
															 List<ParameterType> parameterTypeList,
															 CrossParameterType crossParameterType,
															 ReturnValueType returnValueType,
															 Executable executable) {
		List<ConstrainedParameter> parameterMetaData = constrainedParameterBuilder.buildConstrainedParameters(
				parameterTypeList,
				executable,
				defaultPackage
		);

		Set<MetaConstraint<?>> crossParameterConstraints = parseCrossParameterConstraints(
				defaultPackage,
				crossParameterType,
				executable
		);

		// parse the return value
		Set<MetaConstraint<?>> returnValueConstraints = newHashSet();
		Map<Class<?>, Class<?>> groupConversions = newHashMap();
		boolean isCascaded = parseReturnValueType(
				returnValueType,
				executable,
				returnValueConstraints,
				groupConversions,
				defaultPackage

		);

		// TODO HV-919 Support specification of type parameter constraints via XML and API
		return new ConstrainedExecutable(
				ConfigurationSource.XML,
				executable,
				parameterMetaData,
				crossParameterConstraints,
				returnValueConstraints,
				Collections.emptySet(),
				groupConversions,
				getCascadedTypeParameters( executable, isCascaded )
		);
	}

	private Set<MetaConstraint<?>> parseCrossParameterConstraints(String defaultPackage,
																		 CrossParameterType crossParameterType,
																		 Executable executable) {

		Set<MetaConstraint<?>> crossParameterConstraints = newHashSet();
		if ( crossParameterType == null ) {
			return crossParameterConstraints;
		}

		ConstraintLocation constraintLocation = ConstraintLocation.forCrossParameter( executable );

		for ( ConstraintType constraintType : crossParameterType.getConstraint() ) {
			MetaConstraint<?> metaConstraint = metaConstraintBuilder.buildMetaConstraint(
					constraintLocation,
					constraintType,
					ExecutableHelper.getElementType( executable ),
					defaultPackage,
					ConstraintDescriptorImpl.ConstraintType.CROSS_PARAMETER
			);
			crossParameterConstraints.add( metaConstraint );
		}

		// ignore annotations
		if ( crossParameterType.getIgnoreAnnotations() != null ) {
			annotationProcessingOptions.ignoreConstraintAnnotationsForCrossParameterConstraint(
					executable,
					crossParameterType.getIgnoreAnnotations()
			);
		}

		return crossParameterConstraints;
	}

	private boolean parseReturnValueType(ReturnValueType returnValueType,
												Executable executable,
												Set<MetaConstraint<?>> returnValueConstraints,
												Map<Class<?>, Class<?>> groupConversions,
												String defaultPackage) {
		if ( returnValueType == null ) {
			return false;
		}

		ConstraintLocation constraintLocation = ConstraintLocation.forReturnValue( executable );
		for ( ConstraintType constraint : returnValueType.getConstraint() ) {
			MetaConstraint<?> metaConstraint = metaConstraintBuilder.buildMetaConstraint(
					constraintLocation,
					constraint,
					ExecutableHelper.getElementType( executable ),
					defaultPackage,
					ConstraintDescriptorImpl.ConstraintType.GENERIC
			);
			returnValueConstraints.add( metaConstraint );
		}
		groupConversions.putAll(
				groupConversionBuilder.buildGroupConversionMap(
						returnValueType.getConvertGroup(),
						defaultPackage
				)
		);

		// ignore annotations
		if ( returnValueType.getIgnoreAnnotations() != null ) {
			annotationProcessingOptions.ignoreConstraintAnnotationsForReturnValue(
					executable,
					returnValueType.getIgnoreAnnotations()
			);
		}

		return returnValueType.getValid() != null;
	}

	private List<Class<?>> createParameterTypes(List<ParameterType> parameterList,
													   Class<?> beanClass,
													   String defaultPackage) {
		List<Class<?>> parameterTypes = newArrayList();
		for ( ParameterType parameterType : parameterList ) {
			String type = null;
			try {
				type = parameterType.getType();
				Class<?> parameterClass = classLoadingHelper.loadClass( type, defaultPackage );
				parameterTypes.add( parameterClass );
			}
			catch (ValidationException e) {
				throw log.getInvalidParameterTypeException( type, beanClass );
			}
		}

		return parameterTypes;
	}

	private List<CascadingTypeParameter> getCascadedTypeParameters(Executable executable, boolean isCascaded) {
		if ( isCascaded ) {
			boolean isArray = executable instanceof Method && ( (Method) executable ).getReturnType().isArray();
			return Collections.singletonList( isArray
					? CascadingTypeParameter.arrayElement( ReflectionHelper.typeOf( executable ) )
					: CascadingTypeParameter.annotatedObject( ReflectionHelper.typeOf( executable ) ) );
		}
		else {
			return Collections.emptyList();
		}
	}

	/**
	 * Runs the given privileged action, using a privileged block if required.
	 * <p>
	 * <b>NOTE:</b> This must never be changed into a publicly available method to avoid execution of arbitrary
	 * privileged actions within HV's protection domain.
	 */
	private static <T> T run(PrivilegedAction<T> action) {
		return System.getSecurityManager() != null ? AccessController.doPrivileged( action ) : action.run();
	}
}
