package io.digdag.core.database;

import java.util.List;
import java.util.Date;
import java.util.Map;
import java.util.HashMap;
import java.util.stream.Stream;
import java.util.stream.Collectors;
import java.sql.ResultSet;
import java.sql.SQLException;
import com.google.common.base.*;
import com.google.common.collect.*;
import com.google.inject.Inject;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.digdag.core.session.*;
import io.digdag.core.workflow.TaskControl;
import io.digdag.spi.TaskReport;
import io.digdag.spi.RevisionInfo;
import io.digdag.core.workflow.TaskConfig;
import org.skife.jdbi.v2.IDBI;
import org.skife.jdbi.v2.Handle;
import org.skife.jdbi.v2.sqlobject.SqlQuery;
import org.skife.jdbi.v2.sqlobject.SqlUpdate;
import org.skife.jdbi.v2.sqlobject.Bind;
import org.skife.jdbi.v2.sqlobject.GetGeneratedKeys;
import org.skife.jdbi.v2.StatementContext;
import org.skife.jdbi.v2.sqlobject.customizers.Mapper;
import org.skife.jdbi.v2.tweak.ResultSetMapper;
import io.digdag.spi.config.Config;
import io.digdag.core.repository.ResourceConflictException;
import io.digdag.core.repository.ResourceNotFoundException;

public class DatabaseSessionStoreManager
        extends BasicDatabaseStoreManager
        implements SessionStoreManager, SessionStoreManager.SessionBuilderStore, TaskControlStore
{
    public static short NAMESPACE_WORKFLOW_ID = (short) 3;
    public static short NAMESPACE_REPOSITORY_ID = (short) 1;
    public static short NAMESPACE_SITE_ID = (short) 0;

    private final String databaseType;

    private final ConfigMapper cfm;
    private final StoredTaskMapper stm;
    private final Handle handle;
    private final Dao dao;

    @Inject
    public DatabaseSessionStoreManager(IDBI dbi, ConfigMapper cfm, ObjectMapper mapper, DatabaseStoreConfig config)
    {
        this.databaseType = config.getType();
        this.handle = dbi.open();
        JsonMapper<SessionOptions> opm = new JsonMapper<>(mapper, SessionOptions.class);
        JsonMapper<TaskReport> trm = new JsonMapper<>(mapper, TaskReport.class);
        this.cfm = cfm;
        this.stm = new StoredTaskMapper(cfm, trm);
        handle.registerMapper(stm);
        handle.registerMapper(new StoredSessionMapper(cfm, opm));
        handle.registerMapper(new TaskStateSummaryMapper());
        handle.registerMapper(new StoredSessionMonitorMapper(cfm));
        handle.registerMapper(new SessionRelationMapper());
        handle.registerMapper(new TaskRelationMapper());
        handle.registerMapper(new RevisionInfoMapper());
        handle.registerMapper(new DateMapper());
        handle.registerArgumentFactory(cfm.getArgumentFactory());
        handle.registerArgumentFactory(opm.getArgumentFactory());
        handle.registerArgumentFactory(trm.getArgumentFactory());
        this.dao = handle.attach(Dao.class);
    }

    public void close()
    {
        handle.close();
    }

    private String bitAnd(String op1, String op2)
    {
        switch (databaseType) {
        case "h2":
            return "BITAND(" + op1 + ", " + op2 + ")";
        default:
            return op1 + " % " + op2;
        }
    }

    private String bitOr(String op1, String op2)
    {
        switch (databaseType) {
        case "h2":
            return "BITOR(" + op1 + ", " + op2 + ")";
        default:
            return op1 + " | " + op2;
        }
    }

    private String selectTaskDetailsQuery()
    {
        return "select t.*, s.site_id, td.full_name, td.local_config, td.export_config, ts.state_params, ts.carry_params, ts.error, ts.report, " +
                "(select group_concat(upstream_id separator ',') from task_dependencies where downstream_id = t.id) as upstream_ids" +  // TODO postgresql
            " from tasks t " +
            " join sessions s on s.id = t.session_id " +
            " join task_details td on t.id = td.id " +
            " join task_state_details ts on t.id = ts.id ";
    }

    @Override
    public SessionStore getSessionStore(int siteId)
    {
        return new DatabaseSessionStore(siteId);
    }

    @Override
    public StoredSession getSessionById(long sesId)
        throws ResourceNotFoundException
    {
        return requiredResource(
                dao.getSessionById(sesId),
                "session id=%d", sesId);
    }

    @Override
    public Date getStoreTime()
    {
        return dao.now();
    }

    @Override
    public boolean isAnyNotDoneWorkflows()
    {
        // TODO optimize
        return handle.createQuery(
                "select count(*) from tasks" +
                " where parent_id is null" +
                " and state in (" + Stream.of(
                        TaskStateCode.notDoneStates()
                        ).map(it -> Short.toString(it.get())).collect(Collectors.joining(", ")) + ")"
            )
            .mapTo(long.class)
            .first() > 0L;
    }

    @Override
    public List<Long> findAllReadyTaskIds(int maxEntries)
    {
        return dao.findAllTaskIdsByState(TaskStateCode.READY.get(), maxEntries);
    }

    @Override
    public List<TaskStateSummary> findRecentlyChangedTasks(Date updatedSince, long lastId)
    {
        return dao.findRecentlyChangedTasks(updatedSince, lastId, 100);
    }

    @Override
    public List<TaskStateSummary> findTasksByState(TaskStateCode state, long lastId)
    {
        return dao.findTasksByState(state.get(), lastId, 100);
    }

    @Override
    public boolean requestCancelSession(long sesId)
    {
        int n = handle.createStatement("update tasks " +
                " set state_flags = " + bitOr("state_flags", ":flag") +
                " where session_id = :sesId" +
                " and state in (" + Stream.of(
                    TaskStateCode.notDoneStates()
                    ).map(it -> Short.toString(it.get())).collect(Collectors.joining(", ")) + ")"
            )
            .bind("flag", TaskStateFlags.CANCEL_REQUESTED)
            .bind("sesId", sesId)
            .execute();
        return n > 0;
    }

    @Override
    public int trySetRetryWaitingToReady()
    {
        return dao.trySetRetryWaitingToReady();
    }

    @Override
    public <T> Optional<T> lockTaskIfExists(long taskId, TaskLockAction<T> func)
    {
        return handle.inTransaction((handle, ses) -> {
            TaskStateSummary task = dao.lockTask(taskId);
            if (task != null) {
                TaskControl control = new TaskControl(this, task.getId(), task.getState());
                T result = func.call(control);
                return Optional.of(result);
            }
            return Optional.<T>absent();
        });
    }

    @Override
    public <T> Optional<T> lockTaskIfExists(long taskId, TaskLockActionWithDetails<T> func)
    {
        return handle.inTransaction((handle, ses) -> {
            // TODO JOIN + FOR UPDATE doesn't work with H2 database
            TaskStateSummary summary = dao.lockTask(taskId);
            if (summary != null) {
                StoredTask task = getTaskById(summary.getId());
                TaskControl control = new TaskControl(this, task.getId(), task.getState());
                T result = func.call(control, task);
                return Optional.of(result);
            }
            return Optional.<T>absent();
        });
    }

    @Override
    public <T> Optional<T> lockRootTaskIfExists(long sessionId, TaskLockActionWithDetails<T> func)
    {
        return handle.inTransaction((handle, ses) -> {
            TaskStateSummary summary = dao.lockRootTask(sessionId);
            if (summary != null) {
                StoredTask task = getTaskById(summary.getId());
                TaskControl control = new TaskControl(this, task.getId(), task.getState());
                T result = func.call(control, task);
                return Optional.of(result);
            }
            return Optional.<T>absent();
        });
    }

    @Override
    public StoredSession newSession(int siteId, Session newSession, Optional<SessionRelation> relation, SessionBuilderAction func)
        throws ResourceConflictException
    {
        return catchConflict(() -> {
                final long sesId;
                if (relation.isPresent()) {
                    SessionRelation rel = relation.get();
                    sesId = handle.inTransaction((handle, ses) -> {
                        if (rel.getWorkflowSourceId().isPresent()) {
                            // namespace is workflow source id
                            int wfId = rel.getWorkflowSourceId().get();
                            long insertedId = dao.insertSession(siteId, NAMESPACE_WORKFLOW_ID, wfId, newSession.getName(), newSession.getParams(), newSession.getOptions());
                            dao.insertSessionRelation(insertedId, rel.getRepositoryId(), rel.getRevisionId(), wfId);
                            return insertedId;
                        }
                        else {
                            // namespace is revision
                            long insertedId = dao.insertSession(siteId, NAMESPACE_REPOSITORY_ID, rel.getRepositoryId(), newSession.getName(), newSession.getParams(), newSession.getOptions());
                            dao.insertSessionRelation(insertedId, rel.getRepositoryId(), rel.getRevisionId(), null);
                            return insertedId;
                        }
                    });
                }
                else {
                    // namespace is site
                    sesId = dao.insertSession(siteId, NAMESPACE_SITE_ID, siteId, newSession.getName(), newSession.getParams(), newSession.getOptions());
                    // no insert to session_relations
                }
                StoredSession session = dao.getSessionById(siteId, sesId);
                func.call(session, this);
                return session;
            },
            "session name=%s in with %s", newSession.getName(), relation);
    }

    public Optional<RevisionInfo> getAssociatedRevisionInfo(long sesId)
    {
        return Optional.fromNullable(dao.findAssociatedRevisionInfo(sesId));
    }

    //public SessionRelation getSessionRelationById(long sessionId)
    //    throws ResourceNotFoundException
    //{
    //    return requiredResource(
    //            dao.getSessionRelationById(sessionId),
    //            "session id=%d", sessionId);
    //}

    @Override
    public <T> T addRootTask(Task task, TaskLockActionWithDetails<T> func)
    {
        long taskId = dao.insertTask(task.getSessionId(), task.getParentId().orNull(), task.getTaskType().get(), task.getState().get());  // tasks table don't have unique index
        dao.insertTaskDetails(taskId, task.getFullName(), task.getConfig().getLocal(), task.getConfig().getExport());
        dao.insertEmptyTaskStateDetails(taskId);
        TaskControl control = new TaskControl(this, taskId, task.getState());
        StoredTask stored;
        try {
            stored = getTaskById(taskId);
        }
        catch (ResourceNotFoundException ex) {
            throw new IllegalStateException("Database state error", ex);
        }
        return func.call(control, stored);
    }

    @Override
    public long addSubtask(Task task)
    {
        long taskId = dao.insertTask(task.getSessionId(), task.getParentId().orNull(), task.getTaskType().get(), task.getState().get());  // tasks table don't have unique index
        dao.insertTaskDetails(taskId, task.getFullName(), task.getConfig().getLocal(), task.getConfig().getExport());
        dao.insertEmptyTaskStateDetails(taskId);
        return taskId;
    }

    @Override
    public void addMonitors(long sessionId, List<SessionMonitor> monitors)
    {
        for (SessionMonitor monitor : monitors) {
            dao.insertSessionMonitor(sessionId, monitor.getConfig(), monitor.getNextRunTime().getTime() / 1000);  // session_monitors table don't have unique index
        }
    }

    @Override
    public StoredTask getTaskById(long taskId)
        throws ResourceNotFoundException
    {
        return requiredResource(
            handle.createQuery(
                    selectTaskDetailsQuery() + " where t.id = :id"
                )
                .bind("id", taskId)
                .map(stm)
                .first(),
            "task id=%d", taskId);
    }

    @Override
    public void addDependencies(long downstream, List<Long> upstreams)
    {
        for (long upstream : upstreams) {
            dao.insertTaskDependency(downstream, upstream);  // task_dependencies table don't have unique index
        }
    }

    @Override
    public boolean isAnyProgressibleChild(long taskId)
    {
        return handle.createQuery(
                "select id from tasks" +
                " where parent_id = :parentId" +
                " and (" +
                  // a child task is progressing now
                "state in (" + Stream.of(
                        TaskStateCode.progressingStates()
                        )
                        .map(it -> Short.toString(it.get())).collect(Collectors.joining(", ")) + ")" +
                  " or (" +
                    // or, a child task is BLOCKED and
                    "state = " + TaskStateCode.BLOCKED_CODE +
                    // it's ready to run
                    " and not exists (" +
                      "select * from tasks up" +
                      " join task_dependencies dep on up.id = dep.upstream_id" +
                      " where dep.downstream_id = tasks.id" +
                      " and up.state not in (" + Stream.of(
                              TaskStateCode.canRunDownstreamStates()
                              ).map(it -> Short.toString(it.get())).collect(Collectors.joining(", ")) + ")" +
                    ")" +
                  ")" +
                ") limit 1"
            )
            .bind("parentId", taskId)
            .mapTo(Long.class)
            .first() != null;
    }

    @Override
    public List<Config> collectChildrenErrors(long taskId)
    {
        return handle.createQuery(
                "select ts.error from tasks t" +
                " join task_state_details ts on t.id = ts.id" +
                " where parent_id = :parentId" +
                " and error is not null"
            )
            .bind("parentId", taskId)
            .map(new ConfigResultSetMapper(cfm, "error"))
            .list();
    }

    public boolean setState(long taskId, TaskStateCode beforeState, TaskStateCode afterState)
    {
        long n = dao.setState(taskId, beforeState.get(), afterState.get());
        return n > 0;
    }

    public boolean setStateWithSuccessDetails(long taskId, TaskStateCode beforeState, TaskStateCode afterState, Config stateParams, TaskReport report)
    {
        long n = dao.setState(taskId, beforeState.get(), afterState.get());
        if (n > 0) {
            dao.setStateDetails(taskId, stateParams, report.getCarryParams(), null,
                    // TODO create a class for stored report
                    report.getCarryParams().getFactory().create()
                        .set("in", report.getInputs())
                        .set("out", report.getOutputs()));
            return true;
        }
        return false;
    }

    public boolean setStateWithErrorDetails(long taskId, TaskStateCode beforeState, TaskStateCode afterState, Config stateParams, Optional<Integer> retryInterval, Config error)
    {
        long n;
        if (retryInterval.isPresent()) {
            n = dao.setState(taskId, beforeState.get(), afterState.get(), retryInterval.get());
        }
        else {
            n = dao.setState(taskId, beforeState.get(), afterState.get());
        }
        if (n > 0) {
            dao.setStateDetails(taskId, stateParams, null, error, null);
            return true;
        }
        return false;
    }

    public boolean setStateWithStateParamsUpdate(long taskId, TaskStateCode beforeState, TaskStateCode afterState, Config stateParams, Optional<Integer> retryInterval)
    {
        long n;
        if (retryInterval.isPresent()) {
            n = dao.setState(taskId, beforeState.get(), afterState.get(), retryInterval.get());
        }
        else {
            n = dao.setState(taskId, beforeState.get(), afterState.get());
        }
        if (n > 0) {
            dao.setStateDetails(taskId, stateParams, null, null, null);
            return true;
        }
        return false;
    }

    public int trySetChildrenBlockedToReadyOrShortCircuitPlannedOrCanceled(long taskId)
    {
        return handle.createStatement("update tasks" +
                " set updated_at = now(), state = case" +
                " when task_type = " + TaskType.GROUPING_ONLY + " then " + TaskStateCode.PLANNED_CODE +
                " when " + bitAnd("state_flags", Integer.toString(TaskStateFlags.CANCEL_REQUESTED)) + " != 0 then " + TaskStateCode.CANCELED_CODE +
                " else " + TaskStateCode.READY_CODE +
                " end" +
                " where state = " + TaskStateCode.BLOCKED_CODE +
                " and parent_id = :parentId" +
                " and exists (" +
                  "select * from tasks pt" +
                  " where pt.id = tasks.parent_id" +
                  " and pt.state in (" + Stream.of(
                        TaskStateCode.canRunChildrenStates()
                        ).map(it -> Short.toString(it.get())).collect(Collectors.joining(", ")) + ")" +
                " )" +
                " and not exists (" +
                    "select * from tasks up" +
                    " join task_dependencies dep on up.id = dep.upstream_id" +
                    " where dep.downstream_id = tasks.id" +
                    " and up.state not in (" + Stream.of(
                        TaskStateCode.canRunDownstreamStates()
                        ).map(it -> Short.toString(it.get())).collect(Collectors.joining(", ")) + ")" +
                " )")
            .bind("parentId", taskId)
            .execute();
    }

    //public boolean trySetBlockedToReadyOrShortCircuitPlanned(long taskId)
    //{
    //    int n = handle.createStatement("update tasks " +
    //            " set updated_at = now(), state = case task_type" +
    //            " when " + TaskType.GROUPING_ONLY + " then " + TaskStateCode.PLANNED_CODE +
    //            " else " + TaskStateCode.READY_CODE +
    //            " end" +
    //            " where state = " + TaskStateCode.BLOCKED_CODE +
    //            " and id = :taskId")
    //        .bind("taskId", taskId)
    //        .execute();
    //    return n > 0;
    //}

    @Override
    public void lockReadySessionMonitors(Date currentTime, SessionMonitorAction func)
    {
        List<RuntimeException> exceptions = handle.inTransaction((handle, session) -> {
            return dao.lockReadySessionMonitors(currentTime.getTime() / 1000, 10)  // TODO 10 should be configurable?
                .stream()
                .map(monitor -> {
                    try {
                        Optional<Date> nextRunTime = func.schedule(monitor);
                        if (nextRunTime.isPresent()) {
                            dao.updateNextSessionMonitorRunTime(monitor.getId(),
                                    nextRunTime.get().getTime() / 1000);
                        }
                        else {
                            dao.deleteSessionMonitor(monitor.getId());
                        }
                        return null;
                    }
                    catch (RuntimeException ex) {
                        return ex;
                    }
                })
                .filter(exception -> exception != null)
                .collect(Collectors.toList());
        });
        if (!exceptions.isEmpty()) {
            RuntimeException first = exceptions.get(0);
            for (RuntimeException ex : exceptions.subList(1, exceptions.size())) {
                first.addSuppressed(ex);
            }
            throw first;
        }
    }

    @Override
    public List<TaskRelation> getTaskRelations(long sesId)
    {
        return handle.createQuery(
                "select id, parent_id," +
                " (select group_concat(upstream_id separator ',') from task_dependencies where downstream_id = t.id) as upstream_ids" +  // TODO postgresql
                " from tasks t " +
                " where session_id = :id"
            )
            .bind("id", sesId)
            .map(new TaskRelationMapper())
            .list();
    }

    @Override
    public List<Config> getExportParams(List<Long> idList)
    {
        if (idList.isEmpty()) {
            return ImmutableList.of();
        }
        List<IdConfig> list = handle.createQuery(
                "select id, export_config" +
                " from task_details" +
                " where id in ("+idList.stream().map(id -> Long.toString(id)).collect(Collectors.joining(", "))+")"
            )
            .map(new IdConfigMapper(cfm, "export_config"))
            .list();
        return orderIdConfigList(idList, list);
    }

    @Override
    public List<Config> getCarryParams(List<Long> idList)
    {
        if (idList.isEmpty()) {
            return ImmutableList.of();
        }
        List<IdConfig> list = handle.createQuery(
                "select id, carry_params" +
                " from task_state_details" +
                " where id in ("+idList.stream().map(id -> Long.toString(id)).collect(Collectors.joining(", "))+")"
            )
            .map(new IdConfigMapper(cfm, "carry_params"))
            .list();
        return orderIdConfigList(idList, list);
    }

    private List<Config> orderIdConfigList(List<Long> idList, List<IdConfig> list)
    {
        Map<Long, Config> map = new HashMap<>();
        for (IdConfig idConfig : list) {
            map.put(idConfig.id, idConfig.config);
        }
        ImmutableList.Builder<Config> builder = ImmutableList.builder();
        for (long id : idList) {
            Config config = map.get(id);
            if (config != null) {
                builder.add(config);
            }
        }
        return builder.build();
    }

    private class DatabaseSessionStore
            implements SessionStore
    {
        // TODO retry
        private final int siteId;

        public DatabaseSessionStore(int siteId)
        {
            this.siteId = siteId;
        }

        //public List<StoredSession> getAllSessions()
        //{
        //    return dao.getSessions(siteId, Integer.MAX_VALUE, 0L);
        //}

        @Override
        public List<StoredSession> getSessions(int pageSize, Optional<Long> lastId)
        {
            return dao.getSessions(siteId, pageSize, lastId.or(Long.MAX_VALUE));
        }

        @Override
        public List<StoredSession> getSessionsOfRepository(int repositoryId, int pageSize, Optional<Long> lastId)
        {
            return dao.getSessionsOfRepository(siteId, repositoryId, pageSize, lastId.or(Long.MAX_VALUE));
        }

        @Override
        public List<StoredSession> getSessionsOfWorkflow(int workflowSourceId, int pageSize, Optional<Long> lastId)
        {
            return dao.getSessionsOfWorkflow(siteId, workflowSourceId, pageSize, lastId.or(Long.MAX_VALUE));
        }

        @Override
        public StoredSession getSessionById(long sesId)
            throws ResourceNotFoundException
        {
            return requiredResource(
                    dao.getSessionById(siteId, sesId),
                    "session id=%d", sesId);
        }

        @Override
        public StoredSession getSessionByName(String sesName)
            throws ResourceNotFoundException
        {
            return requiredResource(
                    dao.getSessionByName(siteId, sesName),
                    "session id=%s", sesName);
        }

        @Override
        public TaskStateCode getRootState(long sesId)
            throws ResourceNotFoundException
        {
            return TaskStateCode.of(
                    requiredResource(
                        dao.getRootState(siteId, sesId),
                        "session id=%d", sesId));
        }

        //public List<StoredTask> getAllTasks()
        //{
        //    return handle.createQuery(
        //            selectTaskDetailsQuery() +
        //            " where s.site_id = :siteId"
        //        )
        //        .bind("siteId", siteId)
        //        .map(stm)
        //        .list();
        //}

        @Override
        public List<StoredTask> getTasks(long sesId, int pageSize, Optional<Long> lastId)
        {
            return handle.createQuery(
                    selectTaskDetailsQuery() +
                    " where t.id > :lastId" +
                    " and s.site_id = :siteId" +
                    " and t.session_id = :sesId" +
                    " order by t.id" +
                    " limit :limit"
                )
                .bind("siteId", siteId)
                .bind("sesId", sesId)
                .bind("lastId", lastId.or(0L))
                .bind("limit", pageSize)
                .map(stm)
                .list();
        }
    }

    public interface Dao
    {
        @SqlQuery("select now() as date")
        //Date now();
        java.sql.Timestamp now();

        @SqlQuery("select * from sessions s" +
                " where site_id = :siteId" +
                " and s.id < :lastId" +
                " order by s.id desc" +
                " limit :limit")
        List<StoredSession> getSessions(@Bind("siteId") int siteId, @Bind("limit") int limit, @Bind("lastId") long lastId);

        @SqlQuery("select s.* from sessions s" +
                " join session_relations sr on s.id = sr.id" +
                " where site_id = :siteId" +
                " and sr.repository_id = :repoId" +
                " and s.id < :lastId" +
                " order by s.id desc" +
                " limit :limit")
        List<StoredSession> getSessionsOfRepository(@Bind("siteId") int siteId, @Bind("repoId") int repoId, @Bind("limit") int limit, @Bind("lastId") long lastId);

        @SqlQuery("select s.* from sessions s" +
                " join session_relations sr on s.id = sr.id" +
                " where site_id = :siteId" +
                " and sr.workflow_source_id = :wfId" +
                " and s.id < :lastId" +
                " order by s.id desc" +
                " limit :limit")
        List<StoredSession> getSessionsOfWorkflow(@Bind("siteId") int siteId, @Bind("wfId") int wfId, @Bind("limit") int limit, @Bind("lastId") long lastId);

        @SqlQuery("select * from sessions where id = :id limit 1")
        StoredSession getSessionById(@Bind("id") long id);

        @SqlQuery("select * from sessions where site_id = :siteId and id = :id limit 1")
        StoredSession getSessionById(@Bind("siteId") int siteId, @Bind("id") long id);

        @SqlQuery("select * from sessions where site_id = :siteId and name = :name limit 1")
        StoredSession getSessionByName(@Bind("siteId") int siteId, @Bind("name") String name);

        @SqlUpdate("insert into sessions (site_id, namespace_type, namespace_id, name, params, options, created_at)" +
                " values (:siteId, :namespaceType, :namespaceId, :name, :params, :options, now())")
        @GetGeneratedKeys
        long insertSession(@Bind("siteId") int siteId, @Bind("namespaceType") short namespaceType,
                @Bind("namespaceId") int namespaceId, @Bind("name") String name, @Bind("params") Config params, @Bind("options") SessionOptions options);

        @SqlUpdate("insert into session_relations (id, repository_id, revision_id, workflow_source_id)" +
                " values (:id, :repositoryId, :revisionId, :workflowSourceId)")
        void insertSessionRelation(@Bind("id") long id,  @Bind("repositoryId") int repositoryId, @Bind("revisionId") int revisionId,
                @Bind("workflowSourceId") Integer workflowSourceId);

        @SqlQuery("select * from session_relations where id = :id")
        SessionRelation getSessionRelationById(@Bind("id") long sessionId);

        @SqlQuery("select state from tasks t" +
                " join sessions s on t.session_id = s.id" +
                " where s.site_id = :siteId" +
                " and s.id = :id" +
                " and t.parent_id is null" +
                " limit 1")
        Short getRootState(@Bind("siteId") int siteId, @Bind("id") long id);

        @SqlUpdate("insert into session_monitors (session_id, config, next_run_time, created_at, updated_at)" +
                " values (:sessionId, :config, :nextRunTime, now(), now())")
        @GetGeneratedKeys
        long insertSessionMonitor(@Bind("sessionId") long sessionId, @Bind("config") Config config, @Bind("nextRunTime") long nextRunTime);

        @SqlQuery("select rev.id, repo.name as repository_name, rev.name "+
                " from session_relations sr" +
                " join repositories repo on sr.repository_id = repo.id" +
                " join revisions rev on sr.revision_id = rev.id" +
                " where sr.id = :id")
        RevisionInfo findAssociatedRevisionInfo(@Bind("id") long sessionId);

        @SqlQuery("select id from tasks where state = :state limit :limit")
        List<Long> findAllTaskIdsByState(@Bind("state") short state, @Bind("limit") int limit);

        @SqlUpdate("insert into tasks (session_id, parent_id, task_type, state, state_flags, updated_at)" +
                " values (:sessionId, :parentId, :taskType, :state, 0, now())")
        @GetGeneratedKeys
        long insertTask(@Bind("sessionId") long sessionId, @Bind("parentId") Long parentId,
                @Bind("taskType") int taskType, @Bind("state") short state);

        @SqlUpdate("insert into task_details (id, full_name, local_config, export_config)" +
                " values (:id, :fullName, :localConfig, :exportConfig)")
        void insertTaskDetails(@Bind("id") long id, @Bind("fullName") String fullName, @Bind("localConfig") Config localConfig, @Bind("exportConfig") Config exportConfig);

        @SqlUpdate("insert into task_state_details (id)" +
                " values (:id)")
        void insertEmptyTaskStateDetails(@Bind("id") long id);

        @SqlUpdate("insert into task_dependencies (upstream_id, downstream_id)" +
                " values (:upstreamId, :downstreamId)")
        void insertTaskDependency(@Bind("downstreamId") long downstreamId, @Bind("upstreamId") long upstreamId);

        @SqlQuery("select id, parent_id, state, updated_at " +
                " from tasks " +
                " where updated_at > :updatedSince" +
                " or (updated_at = :updatedSince and id > :lastId)" +
                " order by updated_at asc, id asc" +
                " limit :limit")
        List<TaskStateSummary> findRecentlyChangedTasks(@Bind("updatedSince") Date updatedSince, @Bind("lastId") long lastId, @Bind("limit") int limit);

        @SqlQuery("select id, parent_id, state, updated_at " +
                " from tasks " +
                " where state = :state" +
                " and id > :lastId" +
                " order by updated_at asc, id asc" +
                " limit :limit")
        List<TaskStateSummary> findTasksByState(@Bind("state") short state, @Bind("lastId") long lastId, @Bind("limit") int limit);

        @SqlQuery("select id, parent_id, state, updated_at " +
                " from tasks " +
                " where id = :id" +
                " for update")
        TaskStateSummary lockTask(@Bind("id") long taskId);

        @SqlQuery("select id, parent_id, state, updated_at " +
                " from tasks" +
                " where session_id = :sessionId" +
                " and parent_id is null" +
                " for update")
        TaskStateSummary lockRootTask(@Bind("sessionId") long sessionId);

        @SqlQuery("select t.*, s.site_id, td.full_name, td.local_config, td.export_config, ts.state_params, ts.carry_params, ts.error, ts.report " +
                " from tasks t " +
                " join sessions s on s.id = t.session_id " +
                " join task_details td on t.id = td.id " +
                " join task_state_details ts on t.id = ts.id ")
        List<StoredTask> findAllTasks();

        @SqlUpdate("update tasks " +
                " set updated_at = now(), state = :newState" +
                " where id = :id" +
                " and state = :oldState")
        long setState(@Bind("id") long taskId, @Bind("oldState") short oldState, @Bind("newState") short newState);

        @SqlUpdate("update tasks " +
                " set updated_at = now(), state = :newState, retry_at = now() + interval :retryInterval seconds" +
                " where id = :id" +
                " and state = :oldState")
        long setState(@Bind("id") long taskId, @Bind("oldState") short oldState, @Bind("newState") short newState, @Bind("retryInterval") int retryInterval);

        @SqlUpdate("update task_state_details " +
                " set state_params = :stateParams, carry_params = :carryParams, error = :error, report = :report" +
                " where id = :id")
        long setStateDetails(@Bind("id") long taskId, @Bind("stateParams") Config stateParams, @Bind("carryParams") Config carryParams, @Bind("error") Config error, @Bind("report") Config report);

        @SqlUpdate("update tasks " +
                " set updated_at = now(), state = " + TaskStateCode.READY_CODE +
                " where state in (" + TaskStateCode.RETRY_WAITING_CODE +"," + TaskStateCode.GROUP_RETRY_WAITING_CODE + ")")
        int trySetRetryWaitingToReady();

        @SqlQuery("select * from session_monitors" +
                " where next_run_time <= :currentTime" +
                " limit :limit" +
                " for update")
        List<StoredSessionMonitor> lockReadySessionMonitors(@Bind("currentTime") long currentTime, @Bind("limit") int limit);

        @SqlUpdate("update session_monitors" +
                " set next_run_time = :nextRunTime, updated_at = now()" +
                " where id = :id")
        void updateNextSessionMonitorRunTime(@Bind("id") long id, @Bind("nextRunTime") long nextRunTime);

        @SqlUpdate("delete from session_monitors" +
                " where id = :id")
        void deleteSessionMonitor(@Bind("id") long id);
    }

    private static class DateMapper
            implements ResultSetMapper<Date>
    {
        @Override
        public Date map(int index, ResultSet r, StatementContext ctx)
                throws SQLException
        {
            return r.getTimestamp("date");
        }
    }

    private static class StoredSessionMapper
            implements ResultSetMapper<StoredSession>
    {
        private final ConfigMapper cfm;
        private final JsonMapper<SessionOptions> opm;

        public StoredSessionMapper(ConfigMapper cfm, JsonMapper<SessionOptions> opm)
        {
            this.cfm = cfm;
            this.opm = opm;
        }

        @Override
        public StoredSession map(int index, ResultSet r, StatementContext ctx)
                throws SQLException
        {
            return ImmutableStoredSession.builder()
                .id(r.getLong("id"))
                .siteId(r.getInt("site_id"))
                .createdAt(r.getTimestamp("created_at"))
                .name(r.getString("name"))
                .params(cfm.fromResultSetOrEmpty(r, "params"))
                .options(opm.fromResultSet(r, "options"))
                .build();
        }
    }

    private static class StoredTaskMapper
            implements ResultSetMapper<StoredTask>
    {
        private final ConfigMapper cfm;
        private final JsonMapper<TaskReport> trm;

        public StoredTaskMapper(ConfigMapper cfm, JsonMapper<TaskReport> trm)
        {
            this.cfm = cfm;
            this.trm = trm;
        }

        @Override
        public StoredTask map(int index, ResultSet r, StatementContext ctx)
                throws SQLException
        {
            Config reportConfig = cfm.fromResultSetOrEmpty(r, "report");
            TaskReport report = TaskReport.builder()
                .carryParams(cfm.fromResultSetOrEmpty(r, "carry_params"))
                .inputs(reportConfig.getListOrEmpty("in", Config.class))
                .outputs(reportConfig.getListOrEmpty("out", Config.class))
                .build();

            return ImmutableStoredTask.builder()
                .id(r.getLong("id"))
                .siteId(r.getInt("site_id"))
                .upstreams(getLongIdList(r, "upstream_ids"))
                .updatedAt(r.getTimestamp("updated_at"))
                .retryAt(getOptionalDate(r, "retry_at"))
                .stateParams(cfm.fromResultSetOrEmpty(r, "state_params"))
                .report(report)
                .error(cfm.fromResultSet(r, "error"))
                .sessionId(r.getLong("session_id"))
                .parentId(getOptionalLong(r, "parent_id"))
                .fullName(r.getString("full_name"))
                .config(
                        TaskConfig.assumeValidated(
                                cfm.fromResultSetOrEmpty(r, "local_config"),
                                cfm.fromResultSetOrEmpty(r, "export_config")))
                .taskType(TaskType.of(r.getInt("task_type")))
                .state(TaskStateCode.of(r.getInt("state")))
                .stateFlags(TaskStateFlags.of(r.getInt("state_flags")))
                .build();
        }
    }

    private static class TaskStateSummaryMapper
            implements ResultSetMapper<TaskStateSummary>
    {
        @Override
        public TaskStateSummary map(int index, ResultSet r, StatementContext ctx)
                throws SQLException
        {
            return ImmutableTaskStateSummary.builder()
                .id(r.getLong("id"))
                .parentId(getOptionalLong(r, "parent_id"))
                .state(TaskStateCode.of(r.getInt("state")))
                .updatedAt(r.getTimestamp("updated_at"))
                .build();
        }
    }

    private static class SessionRelationMapper
            implements ResultSetMapper<SessionRelation>
    {
        @Override
        public SessionRelation map(int index, ResultSet r, StatementContext ctx)
                throws SQLException
        {
            return ImmutableSessionRelation.builder()
                .repositoryId(r.getInt("repository_id"))
                .revisionId(r.getInt("revision_id"))
                .workflowSourceId(getOptionalInt(r, "workflow_source_id"))
                .build();
        }
    }

    private static class TaskRelationMapper
            implements ResultSetMapper<TaskRelation>
    {
        @Override
        public TaskRelation map(int index, ResultSet r, StatementContext ctx)
                throws SQLException
        {
            return ImmutableTaskRelation.builder()
                .id(r.getInt("id"))
                .parentId(getOptionalLong(r, "parent_id"))
                .upstreams(getLongIdList(r, "upstream_ids"))
                .build();
        }
    }

    private static class RevisionInfoMapper
            implements ResultSetMapper<RevisionInfo>
    {
        @Override
        public RevisionInfo map(int index, ResultSet r, StatementContext ctx)
                throws SQLException
        {
            return RevisionInfo.of(
                    r.getInt("id"),
                    r.getString("repository_name"),
                    r.getString("name"));
        }
    }

    private static class StoredSessionMonitorMapper
            implements ResultSetMapper<StoredSessionMonitor>
    {
        private final ConfigMapper cfm;

        public StoredSessionMonitorMapper(ConfigMapper cfm)
        {
            this.cfm = cfm;
        }

        @Override
        public StoredSessionMonitor map(int index, ResultSet r, StatementContext ctx)
                throws SQLException
        {
            return ImmutableStoredSessionMonitor.builder()
                .id(r.getLong("id"))
                .sessionId(r.getInt("session_id"))
                .config(cfm.fromResultSetOrEmpty(r, "config"))
                .nextRunTime(new Date(r.getLong("next_run_time") * 1000))
                .createdAt(r.getTimestamp("created_at"))
                .updatedAt(r.getTimestamp("updated_at"))
                .build();
        }
    }

    private static class IdConfig
    {
        protected final long id;
        protected final Config config;

        public IdConfig(long id, Config config)
        {
            this.id = id;
            this.config = config;
        }
    }

    private static class IdConfigMapper
            implements ResultSetMapper<IdConfig>
    {
        private final ConfigMapper cfm;
        private final String configColumn;

        public IdConfigMapper(ConfigMapper cfm, String configColumn)
        {
            this.cfm = cfm;
            this.configColumn = configColumn;
        }

        @Override
        public IdConfig map(int index, ResultSet r, StatementContext ctx)
                throws SQLException
        {
            return new IdConfig(
                    r.getLong("id"),
                    cfm.fromResultSetOrEmpty(r, configColumn));
        }
    }

    private static class ConfigResultSetMapper
            implements ResultSetMapper<Config>
    {
        private final ConfigMapper cfm;
        private final String column;

        public ConfigResultSetMapper(ConfigMapper cfm, String column)
        {
            this.cfm = cfm;
            this.column = column;
        }

        @Override
        public Config map(int index, ResultSet r, StatementContext ctx)
                throws SQLException
        {
            return cfm.fromResultSetOrEmpty(r, column);
        }
    }
}
