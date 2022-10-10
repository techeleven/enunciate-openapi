/**
 * Copyright © 2017-2018 Jyske Bank
 * Copyright © 2006-2016 Web Cohesion (info@webcohesion.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dk.jyskebank.tools.enunciate.modules.openapi;

import static java.util.stream.Collectors.toSet;

import java.io.*;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.util.*;
import java.util.stream.Stream;

import com.webcohesion.enunciate.EnunciateContext;
import com.webcohesion.enunciate.EnunciateException;
import com.webcohesion.enunciate.EnunciateLogger;
import com.webcohesion.enunciate.api.ApiRegistrationContext;
import com.webcohesion.enunciate.api.ApiRegistry;
import com.webcohesion.enunciate.api.InterfaceDescriptionFile;
import com.webcohesion.enunciate.api.datatype.Syntax;
import com.webcohesion.enunciate.api.resources.ResourceApi;
import com.webcohesion.enunciate.api.services.ServiceApi;
import com.webcohesion.enunciate.artifacts.FileArtifact;
import com.webcohesion.enunciate.facets.FacetFilter;
import com.webcohesion.enunciate.module.*;
import com.webcohesion.enunciate.util.freemarker.FileDirective;

import dk.jyskebank.tools.enunciate.modules.openapi.components.Components;
import dk.jyskebank.tools.enunciate.modules.openapi.info.Info;
import dk.jyskebank.tools.enunciate.modules.openapi.paths.OperationIds;
import dk.jyskebank.tools.enunciate.modules.openapi.paths.Paths;
import dk.jyskebank.tools.enunciate.modules.openapi.security.SecurityRequirement;
import dk.jyskebank.tools.enunciate.modules.openapi.servers.Servers;
import freemarker.cache.URLTemplateLoader;
import freemarker.core.Environment;
import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateException;
import freemarker.template.TemplateExceptionHandler;

/**
 * <h1>OpenAPI Module</h1>
 * Based on Swagger Module by Ryan Heaton.
 */
public class OpenApiModule extends BasicGeneratingModule implements ApiFeatureProviderModule, ApiRegistryAwareModule,
    ApiRegistryProviderModule {

  private static final String OPENAPI_MODULENAME = "openapi";

  private static final List<String> DEPENDENCY_MODULES =
      Arrays.asList("jackson", "jackson1", "jaxb", "jaxrs", "jaxws", "spring-web");

  private ApiRegistry apiRegistry;
  private File defaultDocsDir;
  private String defaultDocsSubdir = "ui";

  @Override
  public String getName() {
    return OPENAPI_MODULENAME;
  }

  @Override
  public void setApiRegistry(final ApiRegistry registry) {
    this.apiRegistry = registry;
  }

  @Override
  public List<DependencySpec> getDependencySpecifications() {
    return Collections.singletonList(new DependencySpec() {
      @Override
      public boolean accept(final EnunciateModule module) {
        return DEPENDENCY_MODULES.contains(module.getName());
      }

      @Override
      public boolean isFulfilled() {
        return true;
      }

      @Override
      public String toString() {
        return "all api registry provider modules";
      }
    });
  }

  /**
   * The URL to "openapi.fmt".
   *
   * @return The URL to "openapi.fmt".
   * @throws MalformedURLException
   *     if the template location could not be represented as an URL.
   */
  protected URL getTemplateURL() throws MalformedURLException {
    final String template = getFreemarkerProcessingTemplate();
    if (template != null) {
      return enunciate.getConfiguration().resolveFile(template).toURI().toURL();
    } else {
      return OpenApiModule.class.getResource("openapi.fmt");
    }
  }

  @Override
  public void call(final EnunciateContext context) {
    //no-op; work happens with the openapi interface description.
  }

  @Override
  public ApiRegistry getApiRegistry() {
    return new ApiRegistry() {
      @Override
      public List<ServiceApi> getServiceApis(final ApiRegistrationContext context) {
        return Collections.emptyList();
      }

      @Override
      public List<ResourceApi> getResourceApis(final ApiRegistrationContext context) {
        return Collections.emptyList();
      }

      @Override
      public Set<Syntax> getSyntaxes(final ApiRegistrationContext context) {
        return Collections.emptySet();
      }

      @Override
      public InterfaceDescriptionFile getSwaggerUI() {
        final Set<String> facetIncludes = new TreeSet<String>(enunciate.getConfiguration().getFacetIncludes());
        facetIncludes.addAll(getFacetIncludes());
        final Set<String> facetExcludes = new TreeSet<String>(enunciate.getConfiguration().getFacetExcludes());
        facetExcludes.addAll(getFacetExcludes());
        final FacetFilter facetFilter = new FacetFilter(facetIncludes, facetExcludes);

        final ApiRegistrationContext context = new OpenAPIRegistrationContext(facetFilter);
        final List<ResourceApi> resourceApis = apiRegistry.getResourceApis(context);

        if (resourceApis == null || resourceApis.isEmpty()) {
          info("No resource APIs registered: Swagger UI will not be generated.");
        }

        return new OpenApiInterfaceDescription(resourceApis, context);
      }
    };
  }

  private class OpenApiInterfaceDescription implements InterfaceDescriptionFile {

    private final LocalEnunciateModel enunciateModel;

    private final ApiRegistrationContext apiRegistrationContext;

    public OpenApiInterfaceDescription(final List<ResourceApi> resourceApis,
        final ApiRegistrationContext apiRegistrationContext) {
      this.apiRegistrationContext = apiRegistrationContext;
      this.enunciateModel = new LocalEnunciateModel(resourceApis);
    }

    @Override
    public String getHref() {
      return getDocsSubdir() + "/index.html";
    }

    @Override
    public void writeTo(final File srcDir) throws IOException {
      final String subdir = getDocsSubdir();
      writeToFolder(subdir != null ? new File(srcDir, subdir) : srcDir);
    }

    protected void writeToFolder(final File dir) throws IOException {
      final EnunciateLogger logger = enunciate.getLogger();
      final DataTypeReferenceRenderer dataTypeReferenceRenderer =
          new DataTypeReferenceRenderer(logger, doRemoveObjectPrefix());
      final ObjectTypeRenderer objectTypeRenderer =
          new ObjectTypeRenderer(logger, dataTypeReferenceRenderer, getPassThroughAnnotations(),
              getNamespacePrefixMap(), doRemoveObjectPrefix(), disableExamples());

      final OperationIds operationIds = new OperationIds(logger, enunciateModel);

      dir.mkdirs();
      final Map<String, Object> model = new HashMap<>();
      model.put("info", new Info(logger, enunciate.getConfiguration(), context));
      model.put("paths",
          new Paths(logger, dataTypeReferenceRenderer, objectTypeRenderer, operationIds, enunciateModel));
      model.put("servers", new Servers(logger, enunciate.getConfiguration(), config));
      model.put("security",
          new SecurityRequirement(logger, enunciate.getConfiguration(), objectTypeRenderer, config));
      final Set<Syntax> syntaxes = apiRegistry.getSyntaxes(apiRegistrationContext);
      model.put("components", new Components(logger, objectTypeRenderer, syntaxes));
      model.put("file", new FileDirective(dir, logger));

      buildBase(dir);
      try {
        processTemplate(getTemplateURL(), model);
      } catch (final TemplateException e) {
        throw new EnunciateException(e);
      }

      final FileArtifact openapiArtifact = new FileArtifact(getName(), OPENAPI_MODULENAME, dir);
      openapiArtifact.setPublic(false);
      enunciate.addArtifact(openapiArtifact);
    }

    private boolean disableExamples() {
      return Boolean.parseBoolean(config.getString("[@disableExamples]"));
    }

    /**
     * By default, all model objects are prefixed with "json_" in output file openapi.yml.
     * When this configuration property has value <code>true</code>, then this prefix is omitted.
     *
     * Having the prefix caused problems with client code generation.
     */
    private boolean doRemoveObjectPrefix() {
      return Boolean.parseBoolean(config.getString("[@removeObjectPrefix]"));
    }

    /**
     * Get namespace prefix map from enunciate configuration, return empty map if configuration is invalid.
     *
     * @return namespacePrefixMap
     */
    private Map<String, String> getNamespacePrefixMap() {
      if (enunciate.getConfiguration() == null) {
        return Collections.emptyMap();
      }
      return enunciate.getConfiguration().getNamespaces();
    }

  }

  protected String getHost() {
    String host = config.getString("[@host]", null);

    if (host == null) {
      final String root = enunciate.getConfiguration().getApplicationRoot();
      if (root != null) {
        try {
          final URI uri = URI.create(root);
          host = uri.getHost();
          if (uri.getPort() > 0) {
            host += ":" + uri.getPort();
          }
        } catch (final IllegalArgumentException e) {
          host = null;
        }
      }
    }

    return host;
  }

  /**
   * Processes the specified template with the given model.
   *
   * @param templateURL
   *     The template URL.
   * @param model
   *     The root model.
   * @return expanded template
   * @throws IOException
   *     if IO failed.
   * @throws TemplateException
   *     if template expansion failed.
   */
  public String processTemplate(final URL templateURL, final Object model) throws IOException, TemplateException {
    debug("Processing template %s.", templateURL);
    final Configuration configuration = new Configuration(Configuration.VERSION_2_3_22);
    configuration.setLocale(new Locale("en", "US"));

    configuration.setTemplateLoader(new URLTemplateLoader() {
      protected URL getURL(final String name) {
        try {
          return new URL(name);
        } catch (final MalformedURLException e) {
          return null;
        }
      }
    });

    configuration.setTemplateExceptionHandler(new TemplateExceptionHandler() {
      public void handleTemplateException(final TemplateException templateException,
          final Environment environment,
          final Writer writer) throws TemplateException {
        throw templateException;
      }
    });

    configuration.setLocalizedLookup(false);
    configuration.setDefaultEncoding("UTF-8");
    configuration.setObjectWrapper(new OpenApiUIObjectWrapper());
    final Template template = configuration.getTemplate(templateURL.toString());
    final StringWriter unhandledOutput = new StringWriter();
    template.process(model, unhandledOutput);
    unhandledOutput.close();
    return unhandledOutput.toString();
  }

  /**
   * Builds the base output directory.
   *
   * @param buildDir
   *     directory to write the Swagger UI to.
   * @throws IOException
   *     if IO failed.
   */
  protected void buildBase(final File buildDir) throws IOException {
    if (isSkipBase()) {
      debug("Not including base documentation.");
      return;
    }

    final String base = getBase();
    if (base == null) {
      final InputStream discoveredBase =
          OpenApiModule.class.getResourceAsStream("/META-INF/enunciate/openapi-base.zip");
      if (discoveredBase != null) {
        debug("Discovered documentation base at /META-INF/enunciate/openapi-base.zip");
        enunciate.unzip(discoveredBase, buildDir);
      } else {
        debug("Default base to be used for openapi base.");
        enunciate.unzip(loadDefaultBase(), buildDir);
      }
    } else {
      final File baseFile = enunciate.getConfiguration().resolveFile(base);
      if (baseFile.isDirectory()) {
        debug("Directory %s to be used as the documentation base.", baseFile);
        enunciate.copyDir(baseFile, buildDir);
      } else {
        debug("Zip file %s to be extracted as the documentation base.", baseFile);
        enunciate.unzip(Files.newInputStream(baseFile.toPath()), buildDir);
      }
    }
  }

  /**
   * Loads the default base for the swagger ui.
   *
   * @return The default base for the swagger ui.
   */
  protected InputStream loadDefaultBase() {
    final String resourceName = "/openapi-swagger-ui.zip";
    final InputStream swaggerUiStream = OpenApiModule.class.getResourceAsStream(resourceName);
    if (swaggerUiStream == null) {
      throw new IllegalStateException("Did not find " + resourceName + " in classpath");
    }
    return swaggerUiStream;
  }

  /**
   * The cascading stylesheet to use instead of the default.  This is ignored if the 'base' is also set.
   *
   * @return The cascading stylesheet to use.
   */
  public String getCss() {
    return config.getString("[@css]", null);
  }

  public boolean isSkipBase() {
    return Boolean.parseBoolean(config.getString("[@skipBase]", Boolean.FALSE.toString()));
  }

  public String getFreemarkerProcessingTemplate() {
    return config.getString("[@freemarkerProcessingTemplate]", null);
  }

  /**
   * The OpenAPI "base".  The OpenAPI base is the initial contents of the directory
   * where the swagger-ui will be output.  Can be a zip file or a directory.
   *
   * @return The documentation "base".
   */
  public String getBase() {
    return config.getString("[@base]", null);
  }

  public Set<String> getPassThroughAnnotations() {
    final String arg = config.getString("[@passThroughAnnotations]", "");
    return Stream.of(arg.split(","))
        .map(String::trim)
        .filter(s -> !s.isEmpty())
        .collect(toSet());
  }

  public Set<String> getFacetIncludes() {
    final List<Object> includes = config.getList("facets.include[@name]");
    final Set<String> facetIncludes = new TreeSet<>();
    for (final Object include : includes) {
      facetIncludes.add(String.valueOf(include));
    }
    return facetIncludes;
  }

  public Set<String> getFacetExcludes() {
    final List<Object> excludes = config.getList("facets.exclude[@name]");
    final Set<String> facetExcludes = new TreeSet<>();
    for (final Object exclude : excludes) {
      facetExcludes.add(String.valueOf(exclude));
    }
    return facetExcludes;
  }

  public File getDocsDir() {
    final String docsDir = config.getString("[@docsDir]");
    if (docsDir != null) {
      return resolveFile(docsDir);
    }

    return defaultDocsDir != null ? defaultDocsDir : new File(enunciate.getBuildDir(), getName());
  }

  public String getDocsSubdir() {
    return config.getString("[@docsSubdir]", defaultDocsSubdir);
  }

}
