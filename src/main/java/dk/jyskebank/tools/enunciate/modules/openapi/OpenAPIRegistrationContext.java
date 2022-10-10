package dk.jyskebank.tools.enunciate.modules.openapi;

import com.webcohesion.enunciate.api.ApiRegistrationContext;
import com.webcohesion.enunciate.facets.FacetFilter;
import com.webcohesion.enunciate.javac.javadoc.DefaultJavaDocTagHandler;
import com.webcohesion.enunciate.javac.javadoc.JavaDocTagHandler;

public class OpenAPIRegistrationContext implements ApiRegistrationContext {

	private final FacetFilter facetFilter;

	public OpenAPIRegistrationContext(final FacetFilter facetFilter) {
		this.facetFilter = facetFilter;
	}

	@Override
	public JavaDocTagHandler getTagHandler() {
		return DefaultJavaDocTagHandler.INSTANCE;
	}

	@Override
	public FacetFilter getFacetFilter() {
		return this.facetFilter;
	}

}
