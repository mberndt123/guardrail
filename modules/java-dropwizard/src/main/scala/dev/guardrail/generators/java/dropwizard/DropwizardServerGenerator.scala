package dev.guardrail.generators.java.dropwizard

import cats.Monad
import cats.data.NonEmptyList
import cats.syntax.all._
import com.github.javaparser.StaticJavaParser
import com.github.javaparser.ast.ImportDeclaration
import com.github.javaparser.ast.Modifier.Keyword._
import com.github.javaparser.ast.Modifier._
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.NodeList
import com.github.javaparser.ast.`type`.ClassOrInterfaceType
import com.github.javaparser.ast.`type`.PrimitiveType
import com.github.javaparser.ast.`type`.Type
import com.github.javaparser.ast.`type`.UnknownType
import com.github.javaparser.ast.`type`.VoidType
import com.github.javaparser.ast.body._
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr._
import com.github.javaparser.ast.stmt._
import dev.guardrail.AuthImplementation
import dev.guardrail.Context
import dev.guardrail.Target
import dev.guardrail.core.SupportDefinition
import dev.guardrail.core.Tracker
import dev.guardrail.core.extract.ServerRawResponse
import dev.guardrail.generators.CustomExtractionField
import dev.guardrail.generators.LanguageParameter
import dev.guardrail.generators.RenderedRoutes
import dev.guardrail.generators.Server
import dev.guardrail.generators.Servers
import dev.guardrail.generators.TracingField
import dev.guardrail.generators.java.JavaCollectionsGenerator
import dev.guardrail.generators.java.JavaLanguage
import dev.guardrail.generators.java.JavaVavrCollectionsGenerator
import dev.guardrail.generators.java.SerializationHelpers
import dev.guardrail.generators.java.syntax._
import dev.guardrail.generators.spi.CollectionsGeneratorLoader
import dev.guardrail.generators.spi.ModuleLoadResult
import dev.guardrail.generators.spi.ServerGeneratorLoader
import dev.guardrail.javaext.helpers.ResponseHelpers
import dev.guardrail.shims._
import dev.guardrail.terms.ApplicationJson
import dev.guardrail.terms.BinaryContent
import dev.guardrail.terms.CollectionsLibTerms
import dev.guardrail.terms.ContentType
import dev.guardrail.terms.LanguageTerms
import dev.guardrail.terms.MultipartFormData
import dev.guardrail.terms.OctetStream
import dev.guardrail.terms.Response
import dev.guardrail.terms.Responses
import dev.guardrail.terms.RouteMeta
import dev.guardrail.terms.SecurityScheme
import dev.guardrail.terms.SwaggerTerms
import dev.guardrail.terms.TextContent
import dev.guardrail.terms.TextPlain
import dev.guardrail.terms.UrlencodedFormData
import dev.guardrail.terms.collections.CollectionsAbstraction
import dev.guardrail.terms.collections.JavaStdLibCollections
import dev.guardrail.terms.collections.JavaVavrCollections
import dev.guardrail.terms.framework.FrameworkTerms
import dev.guardrail.terms.protocol.StrictProtocolElems
import dev.guardrail.terms.server._
import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.Operation

import scala.compat.java8.OptionConverters._
import scala.concurrent.Future
import scala.language.existentials
import scala.reflect.runtime.universe.typeTag

class DropwizardServerGeneratorLoader extends ServerGeneratorLoader {
  type L = JavaLanguage
  override def reified = typeTag[Target[JavaLanguage]]
  val apply =
    ModuleLoadResult.forProduct3(
      ServerGeneratorLoader.label      -> Seq(DropwizardVersion.mapping),
      CollectionsGeneratorLoader.label -> Seq(JavaVavrCollectionsGenerator.mapping, JavaCollectionsGenerator.mapping),
      CollectionsGeneratorLoader.label -> Seq(JavaStdLibCollections.mapping, JavaVavrCollections.mapping)
    )((_, cl, ca) => DropwizardServerGenerator()(cl, ca))
}

object DropwizardServerGenerator {
  def apply()(implicit Cl: CollectionsLibTerms[JavaLanguage, Target], Ca: CollectionsAbstraction[JavaLanguage]): ServerTerms[JavaLanguage, Target] =
    new DropwizardServerGenerator
}

@SuppressWarnings(Array("org.wartremover.warts.NonUnitStatements", "org.wartremover.warts.Null"))
class DropwizardServerGenerator private (implicit Cl: CollectionsLibTerms[JavaLanguage, Target], Ca: CollectionsAbstraction[JavaLanguage])
    extends ServerTerms[JavaLanguage, Target] {

  override implicit def MonadF: Monad[Target] = Target.targetInstances

  override def fromSwagger(context: Context, supportPackage: NonEmptyList[String], basePath: Option[String], frameworkImports: List[JavaLanguage#Import])(
      groupedRoutes: List[(List[String], List[RouteMeta])]
  )(
      protocolElems: List[StrictProtocolElems[JavaLanguage]],
      securitySchemes: Map[String, SecurityScheme[JavaLanguage]],
      components: Tracker[Option[Components]]
  )(implicit
      Fw: FrameworkTerms[JavaLanguage, Target],
      Sc: LanguageTerms[JavaLanguage, Target],
      Cl: CollectionsLibTerms[JavaLanguage, Target],
      Sw: SwaggerTerms[JavaLanguage, Target]
  ): Target[Servers[JavaLanguage]] = {
    import Sw._
    import Sc._
    import dev.guardrail._

    for {
      extraImports       <- getExtraImports(context.tracing, supportPackage)
      supportDefinitions <- generateSupportDefinitions(context.tracing, securitySchemes)
      servers <- groupedRoutes.traverse { case (className, unsortedRoutes) =>
        val routes = unsortedRoutes
          .groupBy(_.path.unwrapTracker.indexOf('{'))
          .view
          .mapValues(_.sortBy(r => (r.path.unwrapTracker, r.method)))
          .toList
          .sortBy(_._1)
          .flatMap(_._2)
        for {
          resourceName <- formatTypeName(className.lastOption.getOrElse(""), Some("Resource"))
          handlerName  <- formatTypeName(className.lastOption.getOrElse(""), Some("Handler"))

          responseServerPair <- routes.traverse { case route @ RouteMeta(path, method, operation, securityRequirements) =>
            for {
              operationId           <- getOperationId(operation)
              responses             <- Responses.getResponses(operationId, operation, protocolElems, components)
              responseClsName       <- formatTypeName(operationId, Some("Response"))
              responseDefinitions   <- generateResponseDefinitions(responseClsName, responses, protocolElems)
              methodName            <- formatMethodName(operationId)
              parameters            <- route.getParameters[JavaLanguage, Target](components, protocolElems)
              customExtractionField <- buildCustomExtractionFields(operation, className, context.customExtraction)
              tracingField          <- buildTracingFields(operation, className, context.tracing)
            } yield (
              responseDefinitions,
              GenerateRouteMeta(operationId, methodName, responseClsName, customExtractionField, tracingField, route, parameters, responses)
            )
          }
          (responseDefinitions, serverOperations) = responseServerPair.unzip
          securityExposure = serverOperations.flatMap(_.routeMeta.securityRequirements) match {
            case Nil => SecurityExposure.Undefined
            case xs  => if (xs.exists(_.optional)) SecurityExposure.Optional else SecurityExposure.Required
          }
          renderedRoutes <- generateRoutes(
            context.tracing,
            resourceName,
            handlerName,
            basePath,
            serverOperations,
            protocolElems,
            securitySchemes,
            securityExposure,
            context.authImplementation
          )
          handlerSrc <- renderHandler(
            handlerName,
            renderedRoutes.methodSigs,
            renderedRoutes.handlerDefinitions,
            responseDefinitions.flatten,
            context.customExtraction,
            context.authImplementation,
            securityExposure
          )
          extraRouteParams <- getExtraRouteParams(
            resourceName,
            context.customExtraction,
            context.tracing,
            context.authImplementation,
            securityExposure
          )
          classSrc <- renderClass(
            resourceName,
            handlerName,
            renderedRoutes.classAnnotations,
            renderedRoutes.routes,
            extraRouteParams,
            responseDefinitions.flatten,
            renderedRoutes.supportDefinitions,
            renderedRoutes.securitySchemesDefinitions,
            context.customExtraction,
            context.authImplementation
          )
        } yield Server[JavaLanguage](className, frameworkImports ++ extraImports, handlerSrc, classSrc)
      }
    } yield Servers[JavaLanguage](servers, supportDefinitions)
  }

  @SuppressWarnings(Array("org.wartremover.warts.TripleQuestionMark"))
  private def toJaxRsAnnotationName: ContentType => Expression = {
    case _: ApplicationJson    => new FieldAccessExpr(new NameExpr("MediaType"), "APPLICATION_JSON")
    case _: UrlencodedFormData => new FieldAccessExpr(new NameExpr("MediaType"), "APPLICATION_FORM_URLENCODED")
    case _: MultipartFormData  => new FieldAccessExpr(new NameExpr("MediaType"), "MULTIPART_FORM_DATA")
    case _: TextPlain          => new FieldAccessExpr(new NameExpr("MediaType"), "TEXT_PLAIN")
    case _: OctetStream        => new FieldAccessExpr(new NameExpr("MediaType"), "APPLICATION_OCTET_STREAM")
    case ct: TextContent       => new StringLiteralExpr(ct.value)
    case ct: BinaryContent     => new StringLiteralExpr(ct.value)
    case _                     => ??? // TODO: What do we do if we get here?
  }

  private val ASYNC_RESPONSE_TYPE   = StaticJavaParser.parseClassOrInterfaceType("AsyncResponse")
  private val RESPONSE_TYPE         = StaticJavaParser.parseClassOrInterfaceType("Response")
  private val RESPONSE_BUILDER_TYPE = StaticJavaParser.parseClassOrInterfaceType("Response.ResponseBuilder")
  private val LOGGER_TYPE           = StaticJavaParser.parseClassOrInterfaceType("Logger")
  private val FILE_TYPE             = StaticJavaParser.parseClassOrInterfaceType("java.io.File")

  private val INSTANT_PARAM_TYPE          = StaticJavaParser.parseClassOrInterfaceType("GuardrailJerseySupport.Jsr310.InstantParam")
  private val OFFSET_DATE_TIME_PARAM_TYPE = StaticJavaParser.parseClassOrInterfaceType("GuardrailJerseySupport.Jsr310.OffsetDateTimeParam")
  private val ZONED_DATE_TIME_PARAM_TYPE  = StaticJavaParser.parseClassOrInterfaceType("GuardrailJerseySupport.Jsr310.ZonedDateTimeParam")
  private val LOCAL_DATE_TIME_PARAM_TYPE  = StaticJavaParser.parseClassOrInterfaceType("GuardrailJerseySupport.Jsr310.LocalDateTimeParam")
  private val LOCAL_DATE_PARAM_TYPE       = StaticJavaParser.parseClassOrInterfaceType("GuardrailJerseySupport.Jsr310.LocalDateParam")
  private val LOCAL_TIME_PARAM_TYPE       = StaticJavaParser.parseClassOrInterfaceType("GuardrailJerseySupport.Jsr310.LocalTimeParam")
  private val OFFSET_TIME_PARAM_TYPE      = StaticJavaParser.parseClassOrInterfaceType("GuardrailJerseySupport.Jsr310.OffsetTimeParam")
  private val DURATION_PARAM_TYPE         = StaticJavaParser.parseClassOrInterfaceType("GuardrailJerseySupport.Jsr310.DurationParam")

  private def generateResponseSuperClass(name: String): Target[ClassOrInterfaceDeclaration] =
    Target.log.function("generateResponseSuperClass") {
      for {
        _ <- Target.log.info(s"Name: ${name}")
        cls = new ClassOrInterfaceDeclaration(new NodeList(abstractModifier), false, name)

        _ = cls.addAnnotation(generatedAnnotation(getClass))

        _ = cls.addField(PrimitiveType.intType, "statusCode", PRIVATE, FINAL)

        _ = cls
          .addConstructor()
          .addParameter(new Parameter(new NodeList(finalModifier), PrimitiveType.intType, new SimpleName("statusCode")))
          .setBody(
            new BlockStmt(
              new NodeList(
                new ExpressionStmt(new AssignExpr(new FieldAccessExpr(new ThisExpr, "statusCode"), new NameExpr("statusCode"), AssignExpr.Operator.ASSIGN))
              )
            )
          )

        _ = cls
          .addMethod(s"getStatusCode", PUBLIC)
          .setType(PrimitiveType.intType)
          .setBody(
            new BlockStmt(
              new NodeList(
                new ReturnStmt(new FieldAccessExpr(new ThisExpr, "statusCode"))
              )
            )
          )
      } yield cls
    }

  private def generateResponseClass(
      superClassType: ClassOrInterfaceType,
      response: Response[JavaLanguage],
      errorEntityFallbackType: Option[Type]
  ): Target[(ClassOrInterfaceDeclaration, BodyDeclaration[_ <: BodyDeclaration[_]])] = {
    val clsName = response.statusCodeName.asString
    for {
      clsType <- safeParseClassOrInterfaceType(clsName)
    } yield {
      val cls = new ClassOrInterfaceDeclaration(new NodeList(publicModifier, staticModifier), false, clsName)
        .setExtendedTypes(new NodeList(superClassType))
        .addAnnotation(generatedAnnotation(getClass))

      val (classDecls, creator) = response.value
        .map(_._2)
        .orElse {
          if (response.statusCode >= 400 && response.statusCode <= 599) {
            errorEntityFallbackType
          } else {
            None
          }
        }
        .fold[(List[BodyDeclaration[_ <: BodyDeclaration[_]]], BodyDeclaration[_ <: BodyDeclaration[_]])] {
          val constructor = new ConstructorDeclaration(new NodeList(privateModifier), clsName)
          val _ = constructor.setBody(
            new BlockStmt(
              new NodeList(
                new ExpressionStmt(
                  new MethodCallExpr(
                    "super",
                    new IntegerLiteralExpr(response.statusCode.toString)
                  )
                )
              )
            )
          )

          val creator = new FieldDeclaration(
            new NodeList(publicModifier, staticModifier, finalModifier),
            new VariableDeclarator(clsType, clsName, new ObjectCreationExpr(null, clsType, new NodeList))
          )

          (List(constructor), creator)
        } { valueType =>
          val constructParam = new Parameter(new NodeList(finalModifier), valueType.unbox, new SimpleName("entityBody"))

          val constructor = new ConstructorDeclaration(new NodeList(privateModifier), clsName)
            .addParameter(constructParam)
            .setBody(
              new BlockStmt(
                new NodeList(
                  new ExpressionStmt(
                    new MethodCallExpr(
                      "super",
                      new IntegerLiteralExpr(response.statusCode.toString)
                    )
                  ),
                  new ExpressionStmt(
                    new AssignExpr(
                      new FieldAccessExpr(new ThisExpr, constructParam.getNameAsString),
                      constructParam.getNameAsExpression,
                      AssignExpr.Operator.ASSIGN
                    )
                  )
                )
              )
            )

          val entityBodyField = new FieldDeclaration(
            new NodeList(privateModifier, finalModifier),
            new VariableDeclarator(valueType, "entityBody")
          )

          val entityBodyGetter = new MethodDeclaration(new NodeList(publicModifier), valueType, "getEntityBody")
            .setBody(
              new BlockStmt(
                new NodeList(
                  new ReturnStmt(new FieldAccessExpr(new ThisExpr, "entityBody"))
                )
              )
            )

          val creator = new MethodDeclaration(new NodeList(publicModifier, staticModifier), clsType, clsName)
            .addParameter(constructParam)
            .setBody(
              new BlockStmt(
                new NodeList(
                  new ReturnStmt(new ObjectCreationExpr(null, clsType, new NodeList(constructParam.getNameAsExpression)))
                )
              )
            )

          (List(constructor, entityBodyField, entityBodyGetter), creator)
        }

      sortDefinitions(classDecls).foreach(cls.addMember)
      (cls, creator)
    }
  }

  private def getExtraImports(tracing: Boolean, supportPackage: NonEmptyList[String]): Target[List[ImportDeclaration]] =
    List(
      "javax.inject.Inject",
      "javax.validation.constraints.NotNull",
      "javax.ws.rs.Consumes",
      "javax.ws.rs.DELETE",
      "javax.ws.rs.FormParam",
      "javax.ws.rs.GET",
      "javax.ws.rs.HEAD",
      "javax.ws.rs.HeaderParam",
      "javax.ws.rs.OPTIONS",
      "javax.ws.rs.POST",
      "javax.ws.rs.PUT",
      "javax.ws.rs.Path",
      "javax.ws.rs.PathParam",
      "javax.ws.rs.Produces",
      "javax.ws.rs.QueryParam",
      "javax.ws.rs.container.AsyncResponse",
      "javax.ws.rs.container.Suspended",
      "javax.ws.rs.core.MediaType",
      "javax.ws.rs.core.Response",
      "org.glassfish.jersey.media.multipart.FormDataParam",
      "org.hibernate.validator.valuehandling.UnwrapValidatedValue",
      "org.slf4j.Logger",
      "org.slf4j.LoggerFactory"
    ).traverse(safeParseRawImport)

  private def buildCustomExtractionFields(
      operation: Tracker[Operation],
      resourceName: List[String],
      customExtraction: Boolean
  ): Target[Option[CustomExtractionField[JavaLanguage]]] =
    if (customExtraction) {
      Target.raiseUserError(s"Custom Extraction is not yet supported by this framework")
    } else {
      Target.pure(Option.empty)
    }

  private def buildTracingFields(operation: Tracker[Operation], resourceName: List[String], tracing: Boolean): Target[Option[TracingField[JavaLanguage]]] =
    if (tracing) {
      Target.raiseUserError(s"Tracing is not yet supported by this framework")
    } else {
      Target.pure(Option.empty)
    }

  private def generateRoutes(
      tracing: Boolean,
      resourceName: String,
      handlerName: String,
      basePath: Option[String],
      routes: List[GenerateRouteMeta[JavaLanguage]],
      protocolElems: List[StrictProtocolElems[JavaLanguage]],
      securitySchemes: Map[String, SecurityScheme[JavaLanguage]],
      securityExposure: SecurityExposure,
      authImplementation: AuthImplementation
  ): Target[RenderedRoutes[JavaLanguage]] = {
    import Ca._

    for {
      resourceType <- safeParseClassOrInterfaceType(resourceName)
      handlerType  <- safeParseClassOrInterfaceType(handlerName)
      basePathComponents = basePath.toList.flatMap(ResponseHelpers.splitPathComponents)
      commonPathPrefix   = ResponseHelpers.findPathPrefix(routes.map(_.routeMeta.path.unwrapTracker))
      routeMethodsAndHandlerMethodSigs <- routes
        .traverse {
          case GenerateRouteMeta(
                _,
                methodName,
                responseClsName,
                customExtractionFields,
                tracingFields,
                sr @ RouteMeta(path, httpMethod, operation, securityRequirements),
                parameters,
                responses
              ) =>
            parameters.parameters.foreach(p => p.param.setType(p.param.getType.unbox))

            val method = new MethodDeclaration(new NodeList(publicModifier), new VoidType, methodName)
              .addAnnotation(new MarkerAnnotationExpr(httpMethod.toString))

            val pathSuffix = ResponseHelpers.splitPathComponents(path.unwrapTracker).drop(commonPathPrefix.length).mkString("/", "/", "")
            if (pathSuffix.nonEmpty && pathSuffix != "/") {
              method.addAnnotation(new SingleMemberAnnotationExpr(new Name("Path"), new StringLiteralExpr(pathSuffix)))
            }

            val allConsumes = operation.downField("consumes", _.consumes).map(_.flatMap(ContentType.unapply)).unwrapTracker
            val consumes    = ResponseHelpers.getBestConsumes(operation, allConsumes, parameters)
            consumes
              .map(c => new SingleMemberAnnotationExpr(new Name("Consumes"), toJaxRsAnnotationName(c)))
              .foreach(method.addAnnotation)

            val allProduces = operation.downField("produces", _.produces).map(_.flatMap(ContentType.unapply)).unwrapTracker
            NonEmptyList
              .fromList(
                responses.value
                  .flatMap(ResponseHelpers.getBestProduces[JavaLanguage](operation, allProduces, _, _.isPlain))
                  .distinct
                  .map(toJaxRsAnnotationName)
              )
              .foreach(producesExprs =>
                method.addAnnotation(
                  new SingleMemberAnnotationExpr(
                    new Name("Produces"),
                    producesExprs.toList match {
                      case singleProduces :: Nil => singleProduces
                      case manyProduces          => new ArrayInitializerExpr(manyProduces.toNodeList)
                    }
                  )
                )
              )

            def transformJsr310Params(parameter: Parameter): Target[Parameter] = {
              val isOptional = parameter.getType.isOptionalType
              val tpe        = if (isOptional) parameter.getType.containedType else parameter.getType

              def transform(to: Type): Target[Parameter] = {
                parameter.setType(if (isOptional) to.liftOptionalType else to)
                if (!isOptional) {
                  parameter.getAnnotations.add(0, new MarkerAnnotationExpr("UnwrapValidatedValue"))
                }
                Target.pure(parameter)
              }

              tpe match {
                case cls: ClassOrInterfaceType if cls.getScope.asScala.forall(_.asString == "java.time") =>
                  cls.getNameAsString match {
                    case "Instant"        => transform(INSTANT_PARAM_TYPE)
                    case "OffsetDateTime" => transform(OFFSET_DATE_TIME_PARAM_TYPE)
                    case "ZonedDateTime"  => transform(ZONED_DATE_TIME_PARAM_TYPE)
                    case "LocalDateTime"  => transform(LOCAL_DATE_TIME_PARAM_TYPE)
                    case "LocalDate"      => transform(LOCAL_DATE_PARAM_TYPE)
                    case "LocalTime"      => transform(LOCAL_TIME_PARAM_TYPE)
                    case "OffsetTime"     => transform(OFFSET_TIME_PARAM_TYPE)
                    case "Duration"       => transform(DURATION_PARAM_TYPE)
                    case _                => Target.pure(parameter)
                  }
                case _ => Target.pure(parameter)
              }
            }

            // When we have a file inside multipart/form-data, we don't want to use InputStream,
            // because that will require the server to buffer the entire contents in memory as it
            // reads in the entire body.  Instead we instruct Dropwizard to write it out to a file
            // on disk and use java.io.File.
            def transformMultipartFile(parameter: Parameter, param: LanguageParameter[JavaLanguage]): Target[Parameter] =
              (param.isFile, param.required) match {
                case (true, true)  => Target.pure(parameter.setType(FILE_TYPE))
                case (true, false) => Cl.liftOptionalType(FILE_TYPE).map(parameter.setType)
                case _             => Target.pure(parameter)
              }

            def addValidationAnnotations(parameter: Parameter, param: LanguageParameter[JavaLanguage]): Parameter = {
              if (param.required) {
                // NB: The order here is actually critical.  In the case where we're using multipart,
                // the @NotNull annotation *must* come before the @FormDataParam annotation.  See:
                // https://github.com/eclipse-ee4j/jersey/issues/3632
                parameter.getAnnotations.add(0, new MarkerAnnotationExpr("NotNull"))

                // Vavr's validation support for some reason requires this.
                if (param.param.getTypeAsString.startsWith("io.vavr.collection.")) {
                  parameter.getAnnotations.add(1, new MarkerAnnotationExpr("UnwrapValidatedValue"))
                }
              }
              parameter
            }

            def stripOptionalFromCollections(parameter: Parameter, param: LanguageParameter[JavaLanguage]): Parameter =
              if (!param.required && parameter.getType.containedType.isListType) {
                parameter.setType(parameter.getType.containedType)
              } else {
                parameter
              }

            def addParamAnnotation(parameter: Parameter, param: LanguageParameter[JavaLanguage], annotationName: String): Parameter =
              parameter.addAnnotation(new SingleMemberAnnotationExpr(new Name(annotationName), new StringLiteralExpr(param.argName.value)))

            def boxParameterTypes(parameter: Parameter): Parameter = {
              if (parameter.getType.isPrimitiveType) {
                parameter.setType(parameter.getType.asPrimitiveType.toBoxedType)
              }
              parameter
            }

            def transformHandlerArg(parameter: Parameter): Expression = {
              val isOptional = parameter.getType.isOptionalType
              val typeName   = if (isOptional) parameter.getType.containedType.asString else parameter.getType.asString
              if (typeName.startsWith("GuardrailJerseySupport.Jsr310.") && typeName.endsWith("Param")) {
                if (isOptional) {
                  new MethodCallExpr(
                    parameter.getNameAsExpression,
                    "map",
                    new NodeList[Expression](new MethodReferenceExpr(new NameExpr(typeName), new NodeList, "get"))
                  )
                } else {
                  new MethodCallExpr(parameter.getNameAsExpression, "get")
                }
              } else {
                parameter.getNameAsExpression
              }
            }

            for {
              annotatedMethodParams <- List(
                (parameters.pathParams, "PathParam"),
                (parameters.headerParams, "HeaderParam"),
                (parameters.queryStringParams, "QueryParam"),
                (parameters.formParams, if (consumes.exists(ContentType.isSubtypeOf[MultipartFormData])) "FormDataParam" else "FormParam")
              ).flatTraverse { case (params, annotationName) =>
                params.traverse { param =>
                  val parameter                  = param.param.clone()
                  val optionalCollectionStripped = stripOptionalFromCollections(parameter, param)
                  val annotated                  = addParamAnnotation(optionalCollectionStripped, param, annotationName)
                  for {
                    dateTransformed <- transformJsr310Params(annotated)
                    fileTransformed <- transformMultipartFile(dateTransformed, param)
                  } yield addValidationAnnotations(fileTransformed, param)
                }
              }

              bareMethodParams <- parameters.bodyParams.toList
                .traverse { param =>
                  val parameter                  = param.param.clone()
                  val optionalCollectionStripped = stripOptionalFromCollections(parameter, param)
                  for {
                    dateTransformed <- transformJsr310Params(optionalCollectionStripped)
                  } yield addValidationAnnotations(dateTransformed, param)
                }

              methodParams = (annotatedMethodParams ++ bareMethodParams).map(boxParameterTypes)
              _            = methodParams.foreach(method.addParameter)
              _ = method.addParameter(
                new Parameter(new NodeList(finalModifier), ASYNC_RESPONSE_TYPE, new SimpleName("asyncResponse")).addMarkerAnnotation("Suspended")
              )

              (responseType, resultResumeBody) = ServerRawResponse(operation)
                .filter(_ == true)
                .fold {
                  val responseName = s"$handlerName.$responseClsName"
                  val entitySetterIfTree = NonEmptyList
                    .fromList(responses.value.collect { case Response(statusCodeName, Some(_), _) =>
                      statusCodeName
                    })
                    .map(_.reverse.foldLeft[IfStmt](null) { case (nextIfTree, statusCodeName) =>
                      val responseSubclassType = StaticJavaParser.parseClassOrInterfaceType(s"${responseName}.${statusCodeName}")
                      new IfStmt(
                        new InstanceOfExpr(new NameExpr("result"), responseSubclassType),
                        new BlockStmt(
                          new NodeList(
                            new ExpressionStmt(
                              new MethodCallExpr(
                                new NameExpr("builder"),
                                "entity",
                                new NodeList[Expression](
                                  new MethodCallExpr(
                                    new EnclosedExpr(new CastExpr(responseSubclassType, new NameExpr("result"))),
                                    "getEntityBody"
                                  )
                                )
                              )
                            )
                          )
                        ),
                        nextIfTree
                      )
                    })
                  (
                    StaticJavaParser.parseClassOrInterfaceType(responseName),
                    (
                      List[Statement](
                        new ExpressionStmt(
                          new VariableDeclarationExpr(
                            new VariableDeclarator(
                              RESPONSE_BUILDER_TYPE,
                              "builder",
                              new MethodCallExpr(
                                new NameExpr("Response"),
                                "status",
                                new NodeList[Expression](new MethodCallExpr(new NameExpr("result"), "getStatusCode"))
                              )
                            ),
                            finalModifier
                          )
                        )
                      ) ++ entitySetterIfTree ++ List(
                        new ExpressionStmt(
                          new MethodCallExpr(
                            new NameExpr("asyncResponse"),
                            "resume",
                            new NodeList[Expression](new MethodCallExpr(new NameExpr("builder"), "build"))
                          )
                        )
                      )
                    ).toNodeList
                  )
                } { _ =>
                  (
                    RESPONSE_TYPE,
                    new NodeList(
                      new ExpressionStmt(
                        new MethodCallExpr(
                          new NameExpr("asyncResponse"),
                          "resume",
                          new NodeList[Expression](new NameExpr("result"))
                        )
                      )
                    )
                  )
                }

              resultErrorBody = List[Statement](
                new ExpressionStmt(
                  new MethodCallExpr(
                    new NameExpr("logger"),
                    "error",
                    new NodeList[Expression](
                      new StringLiteralExpr(s"${handlerName}.${methodName} threw an exception ({}): {}"),
                      new MethodCallExpr(new MethodCallExpr(new NameExpr("err"), "getClass"), "getName"),
                      new MethodCallExpr(new NameExpr("err"), "getMessage"),
                      new NameExpr("err")
                    )
                  )
                ),
                new ExpressionStmt(
                  new MethodCallExpr(
                    new NameExpr("asyncResponse"),
                    "resume",
                    new NodeList[Expression](
                      new MethodCallExpr(
                        new MethodCallExpr(
                          new NameExpr("Response"),
                          "status",
                          new NodeList[Expression](new IntegerLiteralExpr("500"))
                        ),
                        "build"
                      )
                    )
                  )
                )
              )

              handlerCall = new MethodCallExpr(
                new FieldAccessExpr(new ThisExpr, "handler"),
                methodName,
                new NodeList[Expression](methodParams.map(transformHandlerArg): _*)
              )

              _ = method.setBody(
                new BlockStmt(
                  new NodeList(
                    new ExpressionStmt(
                      handlerCall
                        .lift[Future[Any]]
                        .onComplete[Throwable, Expression](
                          new LambdaExpr(
                            new Parameter(new UnknownType, "result"),
                            new BlockStmt(resultResumeBody)
                          ).lift[Any => Unit],
                          new LambdaExpr(
                            new Parameter(new UnknownType, "err"),
                            new BlockStmt(resultErrorBody.toNodeList)
                          ).lift[Throwable => Unit]
                        )
                        .value
                    )
                  )
                )
              )

              transformedAnnotatedParams <- (
                parameters.pathParams ++
                  parameters.headerParams ++
                  parameters.queryStringParams ++
                  parameters.formParams
              ).traverse { param =>
                val parameter                  = param.param.clone()
                val optionalCollectionStripped = stripOptionalFromCollections(parameter, param)
                transformMultipartFile(optionalCollectionStripped, param)
              }
              transformedBodyParams = parameters.bodyParams.map(param => stripOptionalFromCollections(param.param.clone(), param))
            } yield {
              val futureResponseType = responseType.liftFutureType
              val handlerMethodSig   = new MethodDeclaration(new NodeList(), futureResponseType, methodName)
              (transformedAnnotatedParams ++ transformedBodyParams).foreach(handlerMethodSig.addParameter)
              handlerMethodSig.setBody(null)

              (method, handlerMethodSig)
            }
        }
        .map(_.unzip)
      (routeMethods, handlerMethodSigs) = routeMethodsAndHandlerMethodSigs
    } yield {
      val resourceConstructor = new ConstructorDeclaration(new NodeList(publicModifier), resourceName)
      resourceConstructor.addAnnotation(new MarkerAnnotationExpr(new Name("Inject")))
      resourceConstructor.addParameter(new Parameter(new NodeList(finalModifier), handlerType, new SimpleName("handler")))
      resourceConstructor.setBody(
        new BlockStmt(
          new NodeList(
            new ExpressionStmt(new AssignExpr(new FieldAccessExpr(new ThisExpr, "handler"), new NameExpr("handler"), AssignExpr.Operator.ASSIGN))
          )
        )
      )

      val annotations = List(
        new SingleMemberAnnotationExpr(new Name("Path"), new StringLiteralExpr((basePathComponents ++ commonPathPrefix).mkString("/", "/", "")))
      )

      val supportDefinitions = List[BodyDeclaration[_ <: BodyDeclaration[_]]](
        new FieldDeclaration(
          new NodeList(privateModifier, staticModifier, finalModifier),
          new VariableDeclarator(
            LOGGER_TYPE,
            "logger",
            new MethodCallExpr(new NameExpr("LoggerFactory"), "getLogger", new NodeList[Expression](new ClassExpr(resourceType)))
          )
        ),
        new FieldDeclaration(new NodeList(privateModifier, finalModifier), new VariableDeclarator(handlerType, "handler")),
        resourceConstructor
      )

      RenderedRoutes[JavaLanguage](routeMethods, annotations, handlerMethodSigs, supportDefinitions, List.empty, List.empty)
    }
  }

  private def getExtraRouteParams(
      resourceName: String,
      customExtraction: Boolean,
      tracing: Boolean,
      authImplementation: AuthImplementation,
      securityExposure: SecurityExposure
  ): Target[List[Parameter]] =
    for {
      customExtraction <-
        if (customExtraction) {
          Target.raiseUserError(s"Custom Extraction is not yet supported by this framework")
        } else Target.pure(List.empty)

      tracing <-
        if (tracing) {
          Target.raiseUserError(s"Tracing is not yet supported by this framework")
        } else Target.pure(List.empty)
    } yield customExtraction ::: tracing

  private def generateResponseDefinitions(
      responseClsName: String,
      responses: Responses[JavaLanguage],
      protocolElems: List[StrictProtocolElems[JavaLanguage]]
  ): Target[List[BodyDeclaration[_ <: BodyDeclaration[_]]]] =
    for {
      abstractResponseClassType <- safeParseClassOrInterfaceType(responseClsName)

      // TODO: verify valueTypes are in protocolElems

      abstractResponseClass <- generateResponseSuperClass(responseClsName)
      responseClasses       <- responses.value.traverse(resp => generateResponseClass(abstractResponseClassType, resp, None))
    } yield {
      sortDefinitions(responseClasses.flatMap { case (cls, creator) => List[BodyDeclaration[_ <: BodyDeclaration[_]]](cls, creator) })
        .foreach(abstractResponseClass.addMember)

      abstractResponseClass :: Nil
    }

  private def generateSupportDefinitions(
      tracing: Boolean,
      securitySchemes: Map[String, SecurityScheme[JavaLanguage]]
  ): Target[List[SupportDefinition[JavaLanguage]]] =
    for {
      annotationImports <- List(
        "java.lang.annotation.ElementType",
        "java.lang.annotation.Retention",
        "java.lang.annotation.RetentionPolicy",
        "java.lang.annotation.Target",
        "javax.ws.rs.HttpMethod"
      ).traverse(safeParseRawImport)

      jersey <- SerializationHelpers.guardrailJerseySupportDef
    } yield {
      def httpMethodAnnotation(name: String): SupportDefinition[JavaLanguage] = {
        val annotationDecl = new AnnotationDeclaration(new NodeList(publicModifier), name)
          .addAnnotation(
            new SingleMemberAnnotationExpr(
              new Name("Target"),
              new ArrayInitializerExpr(new NodeList(new FieldAccessExpr(new NameExpr("ElementType"), "METHOD")))
            )
          )
          .addAnnotation(new SingleMemberAnnotationExpr(new Name("Retention"), new FieldAccessExpr(new NameExpr("RetentionPolicy"), "RUNTIME")))
          .addAnnotation(new SingleMemberAnnotationExpr(new Name("HttpMethod"), new StringLiteralExpr(name)))
        SupportDefinition[JavaLanguage](new Name(name), annotationImports, List(annotationDecl))
      }
      List(
        jersey,
        httpMethodAnnotation("PATCH"),
        httpMethodAnnotation("TRACE")
      )
    }

  private def renderClass(
      className: String,
      handlerName: String,
      classAnnotations: List[com.github.javaparser.ast.expr.AnnotationExpr],
      combinedRouteTerms: List[com.github.javaparser.ast.Node],
      extraRouteParams: List[com.github.javaparser.ast.body.Parameter],
      responseDefinitions: List[com.github.javaparser.ast.body.BodyDeclaration[_ <: com.github.javaparser.ast.body.BodyDeclaration[_]]],
      supportDefinitions: List[com.github.javaparser.ast.body.BodyDeclaration[_ <: com.github.javaparser.ast.body.BodyDeclaration[_]]],
      securitySchemesDefinitions: List[com.github.javaparser.ast.body.BodyDeclaration[_ <: com.github.javaparser.ast.body.BodyDeclaration[_]]],
      customExtraction: Boolean,
      authImplementation: AuthImplementation
  ): Target[List[BodyDeclaration[_ <: BodyDeclaration[_]]]] =
    safeParseSimpleName(className) >>
      safeParseSimpleName(handlerName) >>
      Target.pure(doRenderClass(className, classAnnotations, supportDefinitions, combinedRouteTerms) :: Nil)

  private def renderHandler(
      handlerName: String,
      methodSigs: List[com.github.javaparser.ast.body.MethodDeclaration],
      handlerDefinitions: List[com.github.javaparser.ast.Node],
      responseDefinitions: List[com.github.javaparser.ast.body.BodyDeclaration[_ <: com.github.javaparser.ast.body.BodyDeclaration[_]]],
      customExtraction: Boolean,
      authImplementation: AuthImplementation,
      securityExposure: SecurityExposure
  ): Target[BodyDeclaration[_ <: BodyDeclaration[_]]] = {
    val handlerClass = new ClassOrInterfaceDeclaration(new NodeList(publicModifier), true, handlerName)
    sortDefinitions(methodSigs ++ responseDefinitions).foreach(handlerClass.addMember)
    Target.pure(handlerClass)
  }

  // Lift this function out of RenderClass above to work around a 2.11.x compiler syntax bug
  private def doRenderClass(
      className: String,
      classAnnotations: List[AnnotationExpr],
      supportDefinitions: List[BodyDeclaration[_ <: BodyDeclaration[_]]],
      combinedRouteTerms: List[Node]
  ): ClassOrInterfaceDeclaration = {
    val cls = new ClassOrInterfaceDeclaration(new NodeList(publicModifier), false, className)
    classAnnotations.foreach(cls.addAnnotation)
    sortDefinitions(supportDefinitions ++ combinedRouteTerms.collect { case bd: BodyDeclaration[_] => bd })
      .foreach(cls.addMember)
    cls
  }
}
