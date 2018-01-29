/*
 * Copyright 2015-2018 the original author or authors.
 *
 * All rights reserved. This program and the accompanying materials are
 * made available under the terms of the Eclipse Public License v2.0 which
 * accompanies this distribution and is available at
 *
 * http://www.eclipse.org/legal/epl-v20.html
 */

package org.junit.jupiter.engine.extension;

import static org.junit.jupiter.api.extension.ConditionEvaluationResult.disabled;
import static org.junit.jupiter.api.extension.ConditionEvaluationResult.enabled;
import static org.junit.jupiter.api.extension.ExtensionContext.Namespace.create;
import static org.junit.platform.commons.util.AnnotationUtils.findAnnotation;

import java.util.Arrays;
import java.util.Optional;

import javax.script.Bindings;
import javax.script.Compilable;
import javax.script.CompiledScript;
import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;
import javax.script.SimpleBindings;

import org.junit.jupiter.api.EnabledIf;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtensionContext.Namespace;
import org.junit.platform.commons.JUnitException;
import org.junit.platform.commons.logging.Logger;
import org.junit.platform.commons.logging.LoggerFactory;
import org.junit.platform.commons.util.Preconditions;
import org.junit.platform.commons.util.StringUtils;

/**
 * {@link ExecutionCondition} that supports the {@link EnabledIf @EnabledIf}
 * annotation.
 *
 * @since 5.1
 * @see #evaluateExecutionCondition(ExtensionContext)
 */
class EnabledIfCondition implements ExecutionCondition {

	private static final Logger logger = LoggerFactory.getLogger(EnabledIfCondition.class);

	/**
	 * {@code ConditionEvaluationResult} singleton that is returned when no
	 * {@code @EnabledIf} annotation is (meta-)present on the current element.
	 */
	private static final ConditionEvaluationResult ENABLED_BY_DEFAULT = enabled("@EnabledIf is not present");

	private static final Namespace NAMESPACE = create(EnabledIfCondition.class, ScriptEngine.class);

	// --- Values used in Bindings ---------------------------------------------

	private static final Accessor systemPropertyAccessor = new SystemPropertyAccessor();
	private static final Accessor environmentVariableAccessor = new EnvironmentVariableAccessor();

	// --- Placeholders usable in reason messages ------------------------------

	private static final String REASON_ANNOTATION_PLACEHOLDER = "{annotation}";
	private static final String REASON_SCRIPT_PLACEHOLDER = "{script}";
	private static final String REASON_RESULT_PLACEHOLDER = "{result}";

	@Override
	public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
		Optional<EnabledIf> optionalAnnotation = findAnnotation(context.getElement(), EnabledIf.class);
		if (!optionalAnnotation.isPresent()) {
			return ENABLED_BY_DEFAULT;
		}

		EnabledIf annotation = optionalAnnotation.get();
		Preconditions.notEmpty(annotation.value(), "String[] returned by @EnabledIf.value() must not be empty");

		// Bind context-aware names to their current values
		Accessor configurationParameterAccessor = new ConfigurationParameterAccessor(context);
		Bindings bindings = new SimpleBindings();
		bindings.put(EnabledIf.Bind.JUNIT_TAGS, context.getTags());
		bindings.put(EnabledIf.Bind.JUNIT_UNIQUE_ID, context.getUniqueId());
		bindings.put(EnabledIf.Bind.JUNIT_DISPLAY_NAME, context.getDisplayName());
		bindings.put(EnabledIf.Bind.JUNIT_CONFIGURATION_PARAMETER, configurationParameterAccessor);
		logger.debug(() -> "Context bindings: " + bindings);

		// Already cached the compiled script? Use it!
		Integer annotationHash = Arrays.hashCode(annotation.value()) + annotation.engine().hashCode();
		Compilation compilation = context.getRoot().getStore(NAMESPACE).get(annotationHash, Compilation.class);
		if (compilation != null) {
			System.out.println("Cache hit! " + compilation.script);
			return evaluate(annotation, compilation.script, compilation.compiledScript, bindings);
		}

		// Find script engine
		ScriptEngine scriptEngine = findScriptEngine(annotation.engine());
		logger.debug(() -> "ScriptEngine: " + scriptEngine);

		// Build actual script text from annotation properties
		String script = createScript(annotation, scriptEngine.getFactory().getLanguageName());
		logger.debug(() -> "Script: " + script);

		if (scriptEngine instanceof Compilable) {
			Compilable compilable = (Compilable) scriptEngine;
			CompiledScript compiledScript = compileScript(compilable, script);
			context.getRoot().getStore(NAMESPACE).put(annotationHash, new Compilation(script, compiledScript));
			return evaluate(annotation, script, compiledScript, bindings);
		}

		return evaluate(annotation, scriptEngine, script, bindings);
	}

	ConditionEvaluationResult evaluate(EnabledIf annotation, ScriptEngine scriptEngine, String script,
			Bindings bindings) {
		Object result;
		try {
			if (scriptEngine instanceof Compilable) {
				Compilable compilable = (Compilable) scriptEngine;
				CompiledScript compiledScript = compilable.compile(script);
				result = compiledScript.eval(bindings);
			}
			else {
				result = scriptEngine.eval(script, bindings);
			}
		}
		catch (ScriptException e) {
			String caption = "Evaluation of @EnabledIf script failed.";
			String message = String.format("%s script=`%s`, bindings=%s", caption, script, bindings);
			throw new JUnitException(message, e);
		}

		return evaluate(annotation, script, result);
	}

	private ConditionEvaluationResult evaluate(EnabledIf annotation, String script, CompiledScript compiledScript,
			Bindings bindings) {
		Object result;
		try {
			result = compiledScript.eval(bindings);

		}
		catch (ScriptException e) {
			String caption = "Evaluation of compiled @EnabledIf script failed.";
			String message = String.format("%s script=`%s`, bindings=%s", caption, compiledScript, bindings);
			throw new JUnitException(message, e);
		}

		return evaluate(annotation, script, result);
	}

	private ConditionEvaluationResult evaluate(EnabledIf annotation, String script, Object result) {
		// Trivial case: script returned a custom ConditionEvaluationResult instance.
		if (result instanceof ConditionEvaluationResult) {
			return (ConditionEvaluationResult) result;
		}

		String resultAsString = String.valueOf(result);
		String reason = createReason(annotation, script, resultAsString);
		boolean enabled;

		if (result instanceof Boolean) {
			enabled = (Boolean) result;
		}
		else {
			enabled = Boolean.parseBoolean(resultAsString);
		}

		return enabled ? enabled(reason) : disabled(reason);
	}

	ScriptEngine findScriptEngine(String engine) {
		ScriptEngineManager manager = new ScriptEngineManager();
		ScriptEngine scriptEngine = manager.getEngineByName(engine);
		if (scriptEngine == null) {
			scriptEngine = manager.getEngineByExtension(engine);
		}
		if (scriptEngine == null) {
			scriptEngine = manager.getEngineByMimeType(engine);
		}
		Preconditions.notNull(scriptEngine, () -> "Script engine not found: " + engine);
		Bindings bindings = scriptEngine.getBindings(ScriptContext.GLOBAL_SCOPE);
		bindings.put(EnabledIf.Bind.SYSTEM_PROPERTY, systemPropertyAccessor);
		bindings.put(EnabledIf.Bind.SYSTEM_ENVIRONMENT, environmentVariableAccessor);
		String message = String.format("Engine `%s` triggered creation of: %s", engine, scriptEngine);
		logger.debug(() -> message);
		return scriptEngine;
	}

	String createScript(EnabledIf annotation, String language) {
		String[] lines = annotation.value();

		// trivial case: one liner
		if (lines.length == 1) {
			return lines[0];
		}

		return joinLines(System.lineSeparator(), Arrays.asList(lines));
	}

	private CompiledScript compileScript(Compilable compilable, String script) {
		try {
			return compilable.compile(script);
		}
		catch (ScriptException e) {
			String caption = "Compilation of @EnabledIf script failed.";
			String message = String.format("%s script=`%s`", caption, script);
			throw new JUnitException(message, e);
		}
	}

	String createReason(EnabledIf annotation, String script, String result) {
		String reason = annotation.reason();
		reason = reason.replace(REASON_ANNOTATION_PLACEHOLDER, annotation.toString());
		reason = reason.replace(REASON_SCRIPT_PLACEHOLDER, script);
		reason = reason.replace(REASON_RESULT_PLACEHOLDER, result);
		return reason;
	}

	private String joinLines(String delimiter, Iterable<? extends CharSequence> elements) {
		if (StringUtils.isBlank(delimiter)) {
			delimiter = System.lineSeparator();
		}
		return String.join(delimiter, elements);
	}

	class Compilation {

		final String script;
		final CompiledScript compiledScript;

		Compilation(String script, CompiledScript compiledScript) {
			this.script = script;
			this.compiledScript = compiledScript;
		}
	}

	/**
	 * Used to access named properties without exposing direct access to the
	 * underlying source.
	 */
	// apparently needs to be public (even if in a package private class); otherwise
	// we encounter errors such as the following during script evaluation:
	// TypeError: junitConfigurationParameter.get is not a function
	public interface Accessor {

		/**
		 * Get the value of the property with the supplied name.
		 *
		 * @param name the name of the property to look up
		 * @return the value assigned to the specified name; may be {@code null}
		 */
		String get(String name);

	}

	private static class SystemPropertyAccessor implements Accessor {

		@Override
		public String get(String name) {
			return System.getProperty(name);
		}
	}

	private static class EnvironmentVariableAccessor implements Accessor {

		@Override
		public String get(String name) {
			return System.getenv(name);
		}
	}

	private static class ConfigurationParameterAccessor implements Accessor {

		private final ExtensionContext context;

		ConfigurationParameterAccessor(ExtensionContext context) {
			this.context = context;
		}

		@Override
		public String get(String key) {
			return this.context.getConfigurationParameter(key).orElse(null);
		}
	}

}
