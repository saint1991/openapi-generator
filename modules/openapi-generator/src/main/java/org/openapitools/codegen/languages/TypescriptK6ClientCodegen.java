package org.openapitools.codegen.languages;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Schema;
import org.openapitools.codegen.*;
import io.swagger.models.properties.ArrayProperty;
import io.swagger.models.properties.MapProperty;
import io.swagger.models.properties.Property;
import io.swagger.models.parameters.Parameter;

import java.io.File;
import java.util.*;

import org.apache.commons.lang3.StringUtils;

import org.openapitools.codegen.meta.GeneratorMetadata;
import org.openapitools.codegen.meta.Stability;
import org.openapitools.codegen.model.ModelMap;
import org.openapitools.codegen.model.ModelsMap;
import org.openapitools.codegen.model.OperationMap;
import org.openapitools.codegen.model.OperationsMap;
import org.openapitools.codegen.utils.ModelUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.apache.commons.lang3.StringUtils.capitalize;
import static org.openapitools.codegen.utils.CamelizeOption.LOWERCASE_FIRST_LETTER;
import static org.openapitools.codegen.utils.StringUtils.*;

public class TypescriptK6ClientCodegen extends AbstractTypeScriptClientCodegen {
    private final Logger LOGGER = LoggerFactory.getLogger(TypescriptK6ClientCodegen.class);
    private static String CLASS_NAME_SUFFIX_PATTERN = "^[a-zA-Z0-9]*$";
    private static String FILE_NAME_SUFFIX_PATTERN = "^[a-zA-Z0-9.-]*$";

    public static final String PROJECT_NAME = "projectName";
    private static final String K6_VERSION = "k6Version";
    private static final String USE_JSLIB = "useJslib";
    public static final String MODEL_SUFFIX = "modelSuffix";

    public static final String MODEL_FILE_SUFFIX = "modelFileSuffix";
    public static final String FILE_NAMING = "fileNaming";

    public static final String STRING_ENUMS = "stringEnums";
    private static final String TAGGED_UNIONS = "taggedUnions";

    private static final String DEFAULT_IMPORT_PREFIX = "./";
    private static final String DEFAULT_MODEL_IMPORT_DIRECTORY_PREFIX = "../";

    protected boolean useJslib = true;
    protected String modelSuffix = "";

    protected String modelFileSuffix = "";

    protected String fileNaming = "camelCase";

    protected Boolean stringEnums = false;
    private boolean taggedUnions = false;

    public String getName() {
        return "typescript-k6";
    }

    public String getHelp() {
        return "Generates a typescript-k6 client (experimental).";
    }

    public void setStringEnums(boolean value) {
        stringEnums = value;
    }

    public Boolean getStringEnums() {
        return stringEnums;
    }

    public TypescriptK6ClientCodegen() {
        super();

        outputFolder = "generated-code" + File.separator + "typescript-k6";

        embeddedTemplateDir = templateDir = "typescript-k6";

        modelTemplateFiles.put("model.mustache", ".ts");
        apiTemplateFiles.put("api.mustache", ".ts");

        languageSpecificPrimitives.add("Blob");
        typeMapping.put("file", "Blob");

        apiPackage = "api";
        modelPackage = "model";

        this.generatorMetadata = GeneratorMetadata.newBuilder(this.generatorMetadata)
                .stability(Stability.EXPERIMENTAL)
                .build();

        this.cliOptions.add(new CliOption(K6_VERSION, "The version of k6").defaultValue("0.48.0"));
        this.cliOptions.add(new CliOption(STRING_ENUMS, "Generate string enums instead of objects for enum values.").defaultValue(String.valueOf(this.stringEnums)));
        this.cliOptions.add(CliOption.newBoolean(USE_JSLIB, "Use k6 jslib").defaultValue("true"));
        this.cliOptions.add(CliOption.newBoolean(TAGGED_UNIONS,
                "Use discriminators to create tagged unions instead of extending interfaces.",
                this.taggedUnions));
        this.cliOptions.add(new CliOption(FILE_NAMING, "Naming convention for the output files: 'camelCase', 'kebab-case'.").defaultValue(this.fileNaming));
    }

    @Override
    public void processOpts() {
        super.processOpts();
        supportingFiles.add(new SupportingFile("models.mustache", modelPackage().replace('.', File.separatorChar), "index.ts"));

        if (additionalProperties.containsKey(STRING_ENUMS)) {
            setStringEnums(Boolean.parseBoolean(additionalProperties.get(STRING_ENUMS).toString()));
            additionalProperties.put("stringEnums", getStringEnums());
            if (getStringEnums()) {
                classEnumSeparator = "";
            }
        }

        if (additionalProperties.containsKey(USE_JSLIB)) {
            useJslib = Boolean.parseBoolean(additionalProperties.get(USE_JSLIB).toString());
            additionalProperties.put(USE_JSLIB, useJslib);
        }

        if (additionalProperties.containsKey(MODEL_SUFFIX)) {
            modelSuffix = additionalProperties.get(MODEL_SUFFIX).toString();
            validateClassSuffixArgument("Model", modelSuffix);
        }

        if (additionalProperties.containsKey(MODEL_FILE_SUFFIX)) {
            modelFileSuffix = additionalProperties.get(MODEL_FILE_SUFFIX).toString();
            validateFileSuffixArgument("Model", modelFileSuffix);
        }

        if (additionalProperties.containsKey(TAGGED_UNIONS)) {
            taggedUnions = Boolean.parseBoolean(additionalProperties.get(TAGGED_UNIONS).toString());
        }
    }

    @Override
    public Map<String, ModelsMap> postProcessAllModels(Map<String, ModelsMap> objects) {
        Map<String, ModelsMap> result = super.postProcessAllModels(objects);
        for (ModelsMap entry: result.values()) {
            for (ModelMap mo : entry.getModels()) {
                CodegenModel cm = mo.getModel();
                if (taggedUnions) {
                    mo.put(TAGGED_UNIONS, true);
                    if (cm.discriminator != null && cm.children != null) {
                        for (CodegenModel child : cm.children) {
                            cm.imports.add(child.classname);
                            setChildDiscriminatorValue(cm, child);
                        }
                    }

                    // with tagged union, a child model doesn't extend the parent (all properties are just copied over)
                    // it means we don't need to import that parent any more
                    if (cm.parent != null) {
                        cm.imports.remove(cm.parent);

                        // however, it's possible that the child model contains a recursive reference to the parent
                        // in order to support this case, we update the list of imports from properties once again
                        for (CodegenProperty cp: cm.allVars) {
                            addImportsForPropertyType(cm, cp);
                        }
                        removeSelfReferenceImports(cm);

                    }
                }
                // Add additional filename information for imports
                Set<String> parsedImports = parseImports(cm);
                mo.put("tsImports", toTsImports(cm, parsedImports));
            }
        }
        return result;
    }

    @Override
    protected void addImport(Schema composed, Schema childSchema, CodegenModel model, String modelName) {
        if (composed == null || childSchema == null) {
            return;
        }
        addImport(model, modelName);
    }

    /**
     * Validates that the given string value only contains alpha numeric characters.
     * Throws an IllegalArgumentException, if the string contains any other characters.
     *
     * @param argument The name of the argument being validated. This is only used for displaying an error message.
     * @param value    The value that is being validated.
     */
    private void validateClassSuffixArgument(String argument, String value) {
        if (!value.matches(CLASS_NAME_SUFFIX_PATTERN)) {
            throw new IllegalArgumentException(
                    String.format(Locale.ROOT, "%s class suffix only allows alphanumeric characters.", argument)
            );
        }
    }

    /**
     * Validates that the given string value only contains '-', '.' and alpha numeric characters.
     * Throws an IllegalArgumentException, if the string contains any other characters.
     *
     * @param argument The name of the argument being validated. This is only used for displaying an error message.
     * @param value    The value that is being validated.
     */
    private void validateFileSuffixArgument(String argument, String value) {
        if (!value.matches(FILE_NAME_SUFFIX_PATTERN)) {
            throw new IllegalArgumentException(
                    String.format(Locale.ROOT, "%s file suffix only allows '.', '-' and alphanumeric characters.", argument)
            );
        }
    }

    private void setChildDiscriminatorValue(CodegenModel parent, CodegenModel child) {
        if (
                child.vendorExtensions.isEmpty() ||
                        !child.vendorExtensions.containsKey("x-discriminator-value")
        ) {

            for (CodegenProperty prop : child.allVars) {
                if (prop.baseName.equals(parent.discriminator.getPropertyName())) {

                    for (CodegenDiscriminator.MappedModel mappedModel : parent.discriminator.getMappedModels()) {
                        if (mappedModel.getModelName().equals(child.classname)) {
                            prop.discriminatorValue = mappedModel.getMappingName();
                        }
                    }
                }
            }
        }
    }

    private Set<String> parseImports(CodegenModel cm) {
        Set<String> newImports = new HashSet<>();
        if (!cm.imports.isEmpty()) {
            for (String name : cm.imports) {
                if (name.contains(" | ")) {
                    String[] parts = name.split(" \\| ");
                    Collections.addAll(newImports, parts);
                } else {
                    newImports.add(name);
                }
            }
        }
        return newImports;
    }

    private List<Map<String, String>> toTsImports(CodegenModel cm, Set<String> imports) {
        List<Map<String, String>> tsImports = new ArrayList<>();
        for (String im : imports) {
            if (!im.equals(cm.classname)) {
                HashMap<String, String> tsImport = new HashMap<>();
                // TVG: This is used as class name in the import statements of the model file
                tsImport.put("classname", im);
                tsImport.put("filename", toModelFilename(removeModelPrefixSuffix(im)));
                tsImports.add(tsImport);
            }
        }
        return tsImports;
    }

    @Override
    public String toModelImport(String name) {
        if (importMapping.containsKey(name)) {
            return importMapping.get(name);
        }
        return DEFAULT_MODEL_IMPORT_DIRECTORY_PREFIX + modelPackage() + "/" + toModelFilename(removeModelPrefixSuffix(name)).substring(DEFAULT_IMPORT_PREFIX.length());
    }

    @Override
    public String toModelFilename(String name) {
        if (importMapping.containsKey(name)) {
            return importMapping.get(name);
        }
        return DEFAULT_IMPORT_PREFIX + this.convertUsingFileNamingConvention(super.toModelFilename(name)) + modelFileSuffix;
    }

    public String removeModelPrefixSuffix(String name) {
        String result = name;
        if (!modelSuffix.isBlank() && result.endsWith(modelSuffix)) {
            result = result.substring(0, result.length() - modelSuffix.length());
        }
        String prefix = capitalize(this.modelNamePrefix);
        String suffix = capitalize(this.modelNameSuffix);
        if (prefix.isBlank() && result.startsWith(prefix)) {
            result = result.substring(prefix.length());
        }
        if (!suffix.isBlank() && result.endsWith(suffix)) {
            result = result.substring(0, result.length() - suffix.length());
        }

        return result;
    }

    @Override
    public String toApiFilename(String name) {
        if (name.isBlank()) {
            return "default";
        }
        return this.convertUsingFileNamingConvention(name);
    }

    /**
     * Converts the original name according to the current <code>fileNaming</code> strategy.
     *
     * @param originalName the original name to transform
     * @return the transformed name
     */
    private String convertUsingFileNamingConvention(String originalName) {
        String name = this.removeModelPrefixSuffix(originalName);
        if ("kebab-case".equals(fileNaming)) {
            name = dashize(underscore(name));
        } else {
            name = camelize(name, LOWERCASE_FIRST_LETTER);
        }
        return name;
    }

    @Override
    public OperationsMap postProcessOperationsWithModels(OperationsMap operations, List<ModelMap> models) {
        OperationMap objects = operations.getOperations();

        List<CodegenOperation> ops = objects.getOperation();
        for (CodegenOperation op : ops) {
            // Overwrite path to TypeScript template string, after applying everything we just did.
            op.path = op.path.replace("{", "${");

            if (op.getHasBodyOrFormParams()) {
                if (op.consumes == null) {
                    op.consumes = new ArrayList<>();
                }
                if (op.consumes.isEmpty()) {
                    Map<String, String> consumesMap = new HashMap<String, String>();
                    consumesMap.put("isJson", String.valueOf(op.getHasBodyParam() && !op.getHasFormParams()));
                    consumesMap.put("mediaType", op.getHasFormParams() ? "application/x-www-form-urlencoded" : "application/json");
                    op.consumes.add(consumesMap);
                    op.hasConsumes = true;
                }
            }
        }

        // Add additional filename information for model imports in the services
        List<Map<String, String>> imports = operations.getImports();
        for (Map<String, String> im : imports) {
            // This property is not used in the templates anymore, subject for removal
            im.put("filename", im.get("import"));
            im.put("classname", im.get("classname"));
        }

        return operations;
    }
}
