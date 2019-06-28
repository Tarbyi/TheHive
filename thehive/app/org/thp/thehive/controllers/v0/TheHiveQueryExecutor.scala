package org.thp.thehive.controllers.v0

import scala.reflect.runtime.{currentMirror => rm, universe => ru}

import play.api.libs.json.JsObject

import gremlin.scala.{Graph, GremlinScala, StepLabel, Vertex}
import javax.inject.{Inject, Singleton}
import org.scalactic.Accumulation._
import org.scalactic.Good
import org.thp.scalligraph.auth.AuthContext
import org.thp.scalligraph.controllers.{FSeq, Field, FieldsParser}
import org.thp.scalligraph.models._
import org.thp.scalligraph.query.InputFilter.{and, not, or}
import org.thp.scalligraph.query._
import org.thp.scalligraph.services._
import org.thp.thehive.dto.v0._
import org.thp.thehive.models._
import org.thp.thehive.services._

case class GetCaseParams(id: String)
case class OutputParam(from: Long, to: Long, withSize: Option[Boolean], withStats: Option[Boolean])

@Singleton
class TheHiveQueryExecutor @Inject()(
    caseSrv: CaseSrv,
    taskSrv: TaskSrv,
    observableSrv: ObservableSrv,
    alertSrv: AlertSrv,
    logSrv: LogSrv,
    organisationSrv: OrganisationSrv,
    userSrv: UserSrv,
    implicit val db: Database
) extends QueryExecutor {
  import CaseConversion._
  import TaskConversion._
  import AlertConversion._
  import ObservableConversion._
  import UserConversion._
  import LogConversion._

  override val version: (Int, Int) = 0 -> 0

  override val publicProperties: List[PublicProperty[_, _]] =
    caseProperties(caseSrv, userSrv) ++
      taskProperties(taskSrv, userSrv) ++
      alertProperties ++
      observableProperties ++
      userProperties(userSrv) ++
      logProperties
  override val queries: Seq[ParamQuery[_]] = Seq(
    Query.initWithParam[GetCaseParams, CaseSteps](
      "getCase",
      FieldsParser[GetCaseParams],
      (p, graph, authContext) => caseSrv.get(p.id)(graph).visible(authContext)
    ),
    Query.init[CaseSteps]("listCase", (graph, authContext) => caseSrv.initSteps(graph).visible(authContext)),
    Query.init[UserSteps]("listUser", (graph, authContext) => organisationSrv.get(authContext.organisation)(graph).users),
    Query.init[TaskSteps]("listTask", (graph, authContext) => taskSrv.initSteps(graph).visible(authContext)),
    Query.init[AlertSteps]("listAlert", (graph, _) => alertSrv.initSteps(graph)),
    Query.init[ObservableSteps]("listObservable", (graph, _) => observableSrv.initSteps(graph)),
    Query.init[LogSteps]("listLog", (graph, _) => logSrv.initSteps(graph)),
    Query.withParam[OutputParam, CaseSteps, PagedResult[(RichCase, JsObject)]](
      "page",
      FieldsParser[OutputParam], {
        case (OutputParam(from, to, withSize, withStats), caseSteps, authContext) =>
          caseSteps
            .richPage(from, to, withSize.getOrElse(false)) {
              case c if withStats.contains(true) =>
                caseSteps.newInstance(c).richCaseWithCustomRenderer(caseStatsRenderer(authContext, db, caseSteps.graph)).raw
              case c =>
                caseSteps.newInstance(c).richCase.raw.map(_ -> JsObject.empty)
            }
      }
    ),
    Query.withParam[OutputParam, AlertSteps, AlertSteps](
      "range",
      FieldsParser[OutputParam],
      (range, alertSteps, _) => alertSteps.range(range.from, range.to)
    ),
    Query.withParam[OutputParam, LogSteps, PagedResult[RichLog]](
      "page",
      FieldsParser[OutputParam],
      (range, logSteps, _) => logSteps.richLog.page(range.from, range.to, range.withSize.getOrElse(false))
    ),
    Query.withParam[OutputParam, TaskSteps, PagedResult[RichTask]](
      "page",
      FieldsParser[OutputParam],
      (range, taskSteps, _) => taskSteps.richTask.page(range.from, range.to, range.withSize.getOrElse(false))
    ),
    Query.withParam[OutputParam, UserSteps, PagedResult[RichUser]](
      "page",
      FieldsParser[OutputParam],
      (range, userSteps, authContext) => userSteps.richUser(authContext.organisation).page(range.from, range.to, range.withSize.getOrElse(false))
    ),
    Query.withParam[OutputParam, ObservableSteps, PagedResult[(RichObservable, JsObject)]](
      "page",
      FieldsParser[OutputParam], {
        case (OutputParam(from, to, withSize, withStats), observableSteps, authContext) =>
          observableSteps
            .richPage(from, to, withSize.getOrElse(false)) {
              case c if withStats.contains(true) =>
                observableSteps.newInstance(c).richObservableWithCustomRenderer(observableStatsRenderer(authContext, db, observableSteps.graph)).raw
              case c =>
                observableSteps.newInstance(c).richObservable.raw.map(_ -> JsObject.empty)
            }
      }
    ),
    Query.withParam[OutputParam, AlertSteps, PagedResult[(RichAlert, List[RichObservable])]](
      "page",
      FieldsParser[OutputParam],
      (range, alertSteps, _) =>
        alertSteps
          .richAlert
          .page(range.from, range.to, range.withSize.getOrElse(false))
          .map { richAlert =>
            richAlert -> alertSrv.get(richAlert.alert)(alertSteps.graph).observables.richObservable.toList()
          }
    ),
    Query[CaseSteps, List[RichCase]]("toList", (caseSteps, _) => caseSteps.richCase.toList()),
    Query[TaskSteps, List[RichTask]]("toList", (taskSteps, _) => taskSteps.richTask.toList()),
    Query[UserSteps, List[RichUser]]("toList", (userSteps, authContext) => userSteps.richUser(authContext.organisation).toList()),
    Query[AlertSteps, List[RichAlert]]("toList", (alertSteps, _) => alertSteps.richAlert.toList()),
    Query[ObservableSteps, List[RichObservable]]("toList", (observableSteps, _) => observableSteps.richObservable.toList()),
    Query[CaseSteps, TaskSteps]("listTask", (caseSteps, _) => caseSteps.tasks),
    Query[CaseSteps, List[(RichCase, JsObject)]](
      "listWithStats",
      (caseSteps, authContext) => caseSteps.richCaseWithCustomRenderer(caseStatsRenderer(authContext, db, caseSteps.graph)).toList()
    ),
    new ParentFilterQuery(publicProperties),
    Query.output[RichCase, OutputCase],
    Query.output[RichTask, OutputTask],
    Query.output[RichAlert, OutputAlert],
    Query.output[RichObservable, OutputObservable],
    Query.output[RichUser, OutputUser],
    Query.output[RichLog, OutputLog],
    Query.output[(RichAlert, Seq[RichObservable]), OutputAlert],
    Query.output[(RichCase, JsObject), OutputCase],
    Query.output[(RichObservable, JsObject), OutputObservable]
  )
}

object ParentIdFilter {

  def unapply(field: Field): Option[(String, String)] =
    FieldsParser
      .string
      .on("_type")
      .andThen("parentId")(FieldsParser.string.on("_id"))((_, _))
      .apply(field)
      .fold(Some(_), _ => None)
}

class ParentIdInputFilter(parentId: String) extends InputFilter {
  override def apply[S <: ScalliSteps[_, _, _]](
      publicProperties: List[PublicProperty[_, _]],
      stepType: ru.Type,
      step: S,
      authContext: AuthContext
  ): S = {
    val stepLabel   = StepLabel[Product with Entity]()
    val vertexSteps = step.asInstanceOf[BaseVertexSteps[Product, _]]

    val findParent: GremlinScala[Vertex] =
      if (stepType =:= ru.typeOf[TaskSteps]) vertexSteps.as(stepLabel).raw.inTo[ShareTask].outTo[ShareCase]
      else if (stepType =:= ru.typeOf[ObservableSteps]) vertexSteps.as(stepLabel).raw.inTo[ShareObservable].outTo[ShareCase]
      else if (stepType =:= ru.typeOf[LogSteps]) vertexSteps.as(stepLabel).raw.inTo[TaskLog]
      else ???

    vertexSteps
      .newInstance(findParent.select(stepLabel).asInstanceOf[GremlinScala[Vertex]])
      .asInstanceOf[S]
  }
}

object ParentQueryFilter {

  def unapply(field: Field): Option[(String, Field)] =
    FieldsParser
      .string
      .on("_type")
      .map("parentQuery")(parentType => (parentType, field.get("_query")))
      .apply(field)
      .fold(Some(_), _ => None)
}

class ParentQueryInputFilter(parentFilter: InputFilter) extends InputFilter {
  override def apply[S <: ScalliSteps[_, _, _]](
      publicProperties: List[PublicProperty[_, _]],
      stepType: ru.Type,
      step: S,
      authContext: AuthContext
  ): S = {
    val vertexSteps = step.asInstanceOf[BaseVertexSteps[Product, _]]

    implicit val db: Database = vertexSteps.db
    implicit val graph: Graph = vertexSteps.graph

    val (parentType, linkFn): (ru.Type, GremlinScala[Vertex] => ScalliSteps[_, _, _ <: AnyRef]) =
      if (stepType =:= ru.typeOf[TaskSteps]) ru.typeOf[CaseSteps] -> ((s: GremlinScala[Vertex]) => new CaseSteps(s.inTo[ShareTask].outTo[ShareCase]))
      else if (stepType =:= ru.typeOf[ObservableSteps])
        ru.typeOf[CaseSteps] -> ((s: GremlinScala[Vertex]) => new CaseSteps(s.inTo[ShareObservable].outTo[ShareCase]))
      else if (stepType =:= ru.typeOf[LogSteps]) ru.typeOf[TaskSteps] -> ((s: GremlinScala[Vertex]) => new TaskSteps(s.inTo[TaskLog]))
      else ???
    vertexSteps
      .where(s => parentFilter.apply(publicProperties, parentType, linkFn(s), authContext).raw)
      .asInstanceOf[S]
  }
}

class ParentFilterQuery(publicProperties: List[PublicProperty[_, _]]) extends FilterQuery(publicProperties) {
  override val paramParser: FieldsParser[InputFilter] = FieldsParser("parentIdFilter") {
    case (path, FObjOne("_and", FSeq(fields))) =>
      fields.zipWithIndex.validatedBy { case (field, index) => paramParser((path :/ "_and").toSeq(index), field) }.map(and)
    case (path, FObjOne("_or", FSeq(fields))) =>
      fields.zipWithIndex.validatedBy { case (field, index) => paramParser((path :/ "_or").toSeq(index), field) }.map(or)
    case (path, FObjOne("_not", field))                       => paramParser(path :/ "_not", field).map(not)
    case (_, FObjOne("_parent", ParentIdFilter(_, parentId))) => Good(new ParentIdInputFilter(parentId))
    case (path, FObjOne("_parent", ParentQueryFilter(_, queryField))) =>
      paramParser.apply(path, queryField).map(query => new ParentQueryInputFilter(query))
  }.orElse(InputFilter.fieldsParser)
  override val name: String                   = "filter"
  override def checkFrom(t: ru.Type): Boolean = t <:< ru.typeOf[TaskSteps] || t <:< ru.typeOf[ObservableSteps] || t <:< ru.typeOf[LogSteps]
  override def toType(t: ru.Type): ru.Type    = t
  override def apply(inputFilter: InputFilter, from: Any, authContext: AuthContext): Any =
    inputFilter(publicProperties, rm.classSymbol(from.getClass).toType, from.asInstanceOf[ScalliSteps[_, _, _ <: AnyRef]], authContext)
}
