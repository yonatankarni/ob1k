package com.outbrain.ob1k.swagger.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.outbrain.ob1k.HttpRequestMethodType;
import com.outbrain.ob1k.Request;
import com.outbrain.ob1k.Response;
import com.outbrain.ob1k.Service;
import com.outbrain.ob1k.concurrent.ComposableFuture;
import com.outbrain.ob1k.concurrent.ComposableFutures;
import com.outbrain.ob1k.server.netty.ResponseBuilder;
import com.outbrain.ob1k.server.registry.ServiceRegistry;
import com.outbrain.ob1k.server.registry.endpoints.AbstractServerEndpoint;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiParam;
import io.swagger.models.Info;
import io.swagger.models.Operation;
import io.swagger.models.Path;
import io.swagger.models.Swagger;
import io.swagger.models.Tag;
import io.swagger.models.parameters.QueryParameter;

import java.io.IOException;
import java.io.StringWriter;
import java.lang.reflect.Parameter;
import java.util.Map;

import static com.outbrain.ob1k.HttpRequestMethodType.DELETE;
import static com.outbrain.ob1k.HttpRequestMethodType.GET;
import static com.outbrain.ob1k.HttpRequestMethodType.POST;
import static com.outbrain.ob1k.HttpRequestMethodType.PUT;
import static io.netty.handler.codec.http.HttpHeaders.Names.CONTENT_TYPE;

public class SwaggerService implements Service {

  private final ServiceRegistry serviceRegistry;

  public SwaggerService(ServiceRegistry serviceRegistry) {
    this.serviceRegistry = serviceRegistry;
  }

  public ComposableFuture<Response> apiDocs(Request request) {
    return ComposableFutures.fromValue(buildJsonResponse(buildSwagger(request)));
  }

  private Swagger buildSwagger(Request request) {
    Swagger swagger = new Swagger();
    swagger.host(request.getHeader("Host"));
    swagger.info(buildInfo());
    for (Map.Entry<String, Map<HttpRequestMethodType, AbstractServerEndpoint>> entry :
            serviceRegistry.getRegisteredEndpoints().entrySet()) {
      final String path = entry.getKey();
      for (Map.Entry<HttpRequestMethodType, AbstractServerEndpoint> endpointEntry : entry.getValue().entrySet()) {
        final HttpRequestMethodType methodType = endpointEntry.getKey();
        final AbstractServerEndpoint endpoint = endpointEntry.getValue();
        if (!ignoreEndpoint(endpoint)) {
          Tag tag = buildTag(endpoint.service.getClass());
          swagger.addTag(tag);
          switch (methodType) {
            case GET:
              swagger.path(path, new Path().get(buildOperation(endpoint, tag, methodType)));
              break;
            case POST:
              swagger.path(path, new Path().post(buildOperation(endpoint, tag, methodType)));
              break;
            case PUT:
              swagger.path(path, new Path().put(buildOperation(endpoint, tag, methodType)));
              break;
            case DELETE:
              swagger.path(path, new Path().delete(buildOperation(endpoint, tag, methodType)));
              break;
            case ANY:
              swagger.path(path, new Path().get(buildOperation(endpoint, tag, GET)).
                      post(buildOperation(endpoint, tag, POST)).
                      put(buildOperation(endpoint, tag, PUT)).
                      delete(buildOperation(endpoint, tag, DELETE)));
              break;
          }
        }
      }
    }
    return swagger;
  }

  private Tag buildTag(Class<? extends Service> serviceClass) {
    Api annotation = serviceClass.getAnnotation(Api.class);
    final String name = (annotation != null) ? annotation.value() : serviceClass.getSimpleName();
    final String description = (annotation != null) ? annotation.description() : serviceClass.getCanonicalName();
    return new Tag().name(name).description(description);
  }

  private String buildTitle() {
    final String contextPath = serviceRegistry.getContextPath();
    return contextPath.startsWith("/") ? contextPath.substring(1) : contextPath;
  }

  private Info buildInfo() {
    return new Info().description("API Documentation").version("1.0").title(buildTitle());
  }

  private Operation buildOperation(AbstractServerEndpoint endpoint, Tag tag, HttpRequestMethodType methodType) {
    final Operation operation = new Operation().summary(endpoint.getTargetAsString()).tag(tag.getName()).
            operationId(endpoint.getTargetAsString() + "Using" + methodType.name());
    int i = 0;
    for (Parameter parameter : endpoint.method.getParameters()) {
      ApiParam annotation = parameter.getAnnotation(ApiParam.class);
      final String type = getSwaggerDataType(parameter);
      final String paramName = (annotation != null) ? annotation.name() : endpoint.paramNames[i++];
      final QueryParameter param = new QueryParameter().type(type).name(paramName);
      if (annotation != null) {
        param.description(annotation.value());
      }
      operation.addParameter(param);
    }
    return operation;
  }

  private String getSwaggerDataType(Parameter parameter) {
    // TODO something better
    return "undefined";
  }

  private boolean ignoreEndpoint(AbstractServerEndpoint endpoint) {
    return endpoint.method.getName().equals("handle") ||
            (endpoint.method.getDeclaringClass().getCanonicalName().startsWith("com.outbrain.ob1k") &&
              !endpoint.method.getDeclaringClass().getSimpleName().equals("TestService"));
  }

  private Response buildJsonResponse(Object value) {
    try {
      final StringWriter buffer = new StringWriter();
      ObjectMapper mapper = new ObjectMapper();
      mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
      mapper.writeValue(buffer, value);
      return ResponseBuilder.ok()
              .withContent(buffer.toString())
              .addHeader(CONTENT_TYPE, "application/json; charset=UTF-8")
              .build();

    } catch (IOException e) {
      return ResponseBuilder.fromStatus(HttpResponseStatus.INTERNAL_SERVER_ERROR).
              withContent(e.getMessage()).build();
    }
  }

}
