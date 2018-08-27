/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.kylin.common.persistence;

import com.google.common.collect.Lists;
import org.apache.commons.io.IOUtils;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.FileUtil;
import org.apache.hadoop.fs.Path;
import org.apache.kylin.common.KylinConfig;
import org.apache.kylin.common.util.HadoopUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.text.MessageFormat;
import java.util.List;
import java.util.TreeSet;

public class JDBCResourceDAO {

    private static Logger logger = LoggerFactory.getLogger(JDBCResourceDAO.class);

    private static final String META_TABLE_KEY = "META_TABLE_KEY";

    private static final String META_TABLE_TS = "META_TABLE_TS";

    private static final String META_TABLE_CONTENT = "META_TABLE_CONTENT";

    private JDBCConnectionManager connectionManager;

    private JDBCSqlQueryFormat jdbcSqlQueryFormat;

    private String tableName;

    private String[] tablesName;

    private KylinConfig kylinConfig;

    // For test
    private long queriedSqlNum = 0;

    public JDBCResourceDAO(KylinConfig kylinConfig, String[] tablesName) throws SQLException {
        this.kylinConfig = kylinConfig;
        this.connectionManager = JDBCConnectionManager.getConnectionManager();
        this.jdbcSqlQueryFormat = JDBCSqlQueryFormatProvider.createJDBCSqlQueriesFormat(kylinConfig.getMetadataDialect());
        this.tablesName = tablesName;
        for (int i = 0; i < tablesName.length; i++) {
            createTableIfNeeded(tablesName[i]);
            createIndex("IDX_" + META_TABLE_TS, tablesName[i], META_TABLE_TS);
        }
    }

    public void close() {
        connectionManager.close();
    }

    public JDBCResource getResource(final String resourcePath, final boolean fetchContent, final boolean fetchTimestamp)
            throws SQLException {
        return getResource(resourcePath, fetchContent, fetchTimestamp, false);
    }

    public JDBCResource getResource(final String resourcePath, final boolean fetchContent, final boolean fetchTimestamp,
                                    final boolean isAllowBroken) throws SQLException {
        final JDBCResource resource = new JDBCResource();
        logger.trace("getResource method. resourcePath : {} , fetchConetent : {} , fetch TS : {}", resourcePath,
                fetchContent, fetchTimestamp);
        executeSql(new SqlOperation() {
            @Override
            public void execute(Connection connection) throws SQLException {
                tableName = getMetaTableName(resourcePath);
                pstat = connection.prepareStatement(getKeyEqualSqlString(fetchContent, fetchTimestamp));
                pstat.setString(1, resourcePath);
                rs = pstat.executeQuery();
                if (rs.next()) {
                    resource.setPath(rs.getString(META_TABLE_KEY));
                    if (fetchTimestamp)
                        resource.setTimestamp(rs.getLong(META_TABLE_TS));
                    if (fetchContent) {
                        try {
                            resource.setContent(getInputStream(resourcePath, rs));
                        } catch (Throwable e) {
                            if (!isAllowBroken) {
                                throw new SQLException(e);
                            }

                            final BrokenEntity brokenEntity = new BrokenEntity(resourcePath, e.getMessage());
                            resource.setContent(new BrokenInputStream(brokenEntity));
                            logger.warn(e.getMessage());
                        }
                    }
                }
            }
        });
        if (resource.getPath() != null) {
            return resource;
        } else {
            return null;
        }
    }

    public boolean existResource(final String resourcePath) throws SQLException {
        JDBCResource resource = getResource(resourcePath, false, false);
        return (resource != null);
    }

    public long getResourceTimestamp(final String resourcePath) throws SQLException {
        JDBCResource resource = getResource(resourcePath, false, true);
        return resource == null ? 0 : resource.getTimestamp();
    }

    //fetch primary key only
    public TreeSet<String> listAllResource(final String folderPath, final boolean recursive) throws SQLException {
        final TreeSet<String> allResourceName = new TreeSet<>();
        executeSql(new SqlOperation() {
            @Override
            public void execute(Connection connection) throws SQLException {
                tableName = getMetaTableName(folderPath);
                pstat = connection.prepareStatement(getListResourceSqlString());
                pstat.setString(1, folderPath + "%");
                rs = pstat.executeQuery();
                while (rs.next()) {
                    String path = rs.getString(META_TABLE_KEY);
                    assert path.startsWith(folderPath);
                    if (recursive) {
                        allResourceName.add(path);
                    } else {
                        int cut = path.indexOf('/', folderPath.length());
                        String child = cut < 0 ? path : path.substring(0, cut);
                        allResourceName.add(child);
                    }
                }
            }
        });
        return allResourceName;
    }

    public List<JDBCResource> getAllResource(final String folderPath, final long timeStart, final long timeEndExclusive,
                                             final boolean isAllowBroken) throws SQLException {
        final List<JDBCResource> allResource = Lists.newArrayList();
        executeSql(new SqlOperation() {
            @Override
            public void execute(Connection connection) throws SQLException {
                tableName = getMetaTableName(folderPath);
                pstat = connection.prepareStatement(getAllResourceSqlString());
                pstat.setString(1, folderPath + "%");
                pstat.setLong(2, timeStart);
                pstat.setLong(3, timeEndExclusive);
                rs = pstat.executeQuery();
                while (rs.next()) {
                    String resPath = rs.getString(META_TABLE_KEY);
                    if (checkPath(folderPath, resPath)) {
                        JDBCResource resource = new JDBCResource();
                        resource.setPath(resPath);
                        resource.setTimestamp(rs.getLong(META_TABLE_TS));
                        try {
                            resource.setContent(getInputStream(resPath, rs));
                        } catch (Throwable e) {
                            if (!isAllowBroken) {
                                throw new SQLException(e);
                            }

                            final BrokenEntity brokenEntity = new BrokenEntity(resPath, e.getMessage());
                            resource.setContent(new BrokenInputStream(brokenEntity));
                            logger.warn(e.getMessage());
                        }
                        allResource.add(resource);
                    }
                }
            }
        });
        return allResource;
    }

    private boolean checkPath(String lookForPrefix, String resPath) {
        lookForPrefix = lookForPrefix.endsWith("/") ? lookForPrefix : lookForPrefix + "/";
        assert resPath.startsWith(lookForPrefix);
        int cut = resPath.indexOf('/', lookForPrefix.length());
        return (cut < 0);
    }

    private boolean isJsonMetadata(String resourcePath) {
        String trim = resourcePath.trim();
        return trim.endsWith(".json") || trim.startsWith(ResourceStore.EXECUTE_RESOURCE_ROOT)
                || trim.startsWith(ResourceStore.EXECUTE_OUTPUT_RESOURCE_ROOT);

    }

    public void deleteResource(final String resourcePath) throws SQLException {

        boolean skipHdfs = isJsonMetadata(resourcePath);

        executeSql(new SqlOperation() {
            @Override
            public void execute(Connection connection) throws SQLException {
                tableName = getMetaTableName(resourcePath);
                pstat = connection.prepareStatement(getDeletePstatSql());
                pstat.setString(1, resourcePath);
                pstat.executeUpdate();
            }
        });

        if (!skipHdfs) {
            try {
                deleteHDFSResourceIfExist(resourcePath);
            } catch (Throwable e) {
                throw new SQLException(e);
            }
        }
    }

    private void deleteHDFSResourceIfExist(String resourcePath) throws IOException {

        Path redirectPath = bigCellHDFSPath(resourcePath);
        FileSystem fileSystem = HadoopUtil.getFileSystem(redirectPath);

        if (fileSystem.exists(redirectPath)) {
            fileSystem.delete(redirectPath, true);
        }

    }

    public void putResource(final JDBCResource resource) throws SQLException {
        executeSql(new SqlOperation() {
            @Override
            public void execute(Connection connection) throws SQLException {
                byte[] content = getResourceDataBytes(resource);
                synchronized (resource.getPath().intern()) {
                    boolean existing = existResource(resource.getPath());
                    tableName = getMetaTableName(resource.getPath());
                    if (existing) {
                        pstat = connection.prepareStatement(getReplaceSql());
                        pstat.setLong(1, resource.getTimestamp());
                        pstat.setBlob(2, new BufferedInputStream(new ByteArrayInputStream(content)));
                        pstat.setString(3, resource.getPath());
                    } else {
                        pstat = connection.prepareStatement(getInsertSql());
                        pstat.setString(1, resource.getPath());
                        pstat.setLong(2, resource.getTimestamp());
                        pstat.setBlob(3, new BufferedInputStream(new ByteArrayInputStream(content)));
                    }

                    if (isContentOverflow(content, resource.getPath())) {
                        logger.debug("Overflow! resource path: {}, content size: {}, timeStamp: {}", resource.getPath(),
                                content.length, resource.getTimestamp());
                        if (existing) {
                            pstat.setNull(2, Types.BLOB);
                        } else {
                            pstat.setNull(3, Types.BLOB);
                        }
                        writeLargeCellToHdfs(resource.getPath(), content);
                        try {
                            int result = pstat.executeUpdate();
                            if (result != 1)
                                throw new SQLException();
                        } catch (SQLException e) {
                            rollbackLargeCellFromHdfs(resource.getPath());
                            throw e;
                        }
                        if (existing) {
                            cleanOldLargeCellFromHdfs(resource.getPath());
                        }
                    } else {
                        pstat.executeUpdate();
                    }
                }
            }
        });
    }

    public void checkAndPutResource(final String resPath, final byte[] content, final long oldTS, final long newTS)
            throws SQLException, WriteConflictException {
        logger.trace(
                "execute checkAndPutResource method. resPath : {} , oldTs : {} , newTs : {} , content null ? : {} ",
                resPath, oldTS, newTS, content == null);
        executeSql(new SqlOperation() {
            @Override
            public void execute(Connection connection) throws SQLException {
                synchronized (resPath.intern()) {
                    tableName = getMetaTableName(resPath);
                    if (!existResource(resPath)) {
                        if (oldTS != 0) {
                            throw new IllegalStateException(
                                    "For not exist file. OldTS have to be 0. but Actual oldTS is : " + oldTS);
                        }
                        if (isContentOverflow(content, resPath)) {
                            logger.debug("Overflow! resource path: {}, content size: {}", resPath, content.length);
                            pstat = connection.prepareStatement(getInsertSqlWithoutContent());
                            pstat.setString(1, resPath);
                            pstat.setLong(2, newTS);
                            writeLargeCellToHdfs(resPath, content);
                            try {
                                int result = pstat.executeUpdate();
                                if (result != 1)
                                    throw new SQLException();
                            } catch (SQLException e) {
                                rollbackLargeCellFromHdfs(resPath);
                                throw e;
                            }
                        } else {
                            pstat = connection.prepareStatement(getInsertSql());
                            pstat.setString(1, resPath);
                            pstat.setLong(2, newTS);
                            pstat.setBlob(3, new BufferedInputStream(new ByteArrayInputStream(content)));
                            pstat.executeUpdate();
                        }
                    } else {
                        // Note the checkAndPut trick:
                        // update {0} set {1}=? where {2}=? and {3}=?
                        pstat = connection.prepareStatement(getUpdateSqlWithoutContent());
                        pstat.setLong(1, newTS);
                        pstat.setString(2, resPath);
                        pstat.setLong(3, oldTS);
                        int result = pstat.executeUpdate();
                        if (result != 1) {
                            long realTime = getResourceTimestamp(resPath);
                            throw new WriteConflictException("Overwriting conflict " + resPath + ", expect old TS "
                                    + oldTS + ", but it is " + realTime);
                        }
                        PreparedStatement pstat2 = null;
                        try {
                            // "update {0} set {1}=? where {3}=?"
                            pstat2 = connection.prepareStatement(getUpdateContentSql());
                            if (isContentOverflow(content, resPath)) {
                                logger.debug("Overflow! resource path: {}, content size: {}", resPath, content.length);
                                pstat2.setNull(1, Types.BLOB);
                                pstat2.setString(2, resPath);
                                writeLargeCellToHdfs(resPath, content);
                                try {
                                    int result2 = pstat2.executeUpdate();
                                    if (result2 != 1)
                                        throw new SQLException();
                                } catch (SQLException e) {
                                    rollbackLargeCellFromHdfs(resPath);
                                    throw e;
                                }
                                cleanOldLargeCellFromHdfs(resPath);
                            } else {
                                pstat2.setBinaryStream(1, new BufferedInputStream(new ByteArrayInputStream(content)));
                                pstat2.setString(2, resPath);
                                pstat2.executeUpdate();
                            }
                        } finally {
                            JDBCConnectionManager.closeQuietly(pstat2);
                        }
                    }
                }
            }
        });
    }

    private byte[] getResourceDataBytes(JDBCResource resource) throws SQLException {
        ByteArrayOutputStream bout = null;
        try {
            bout = new ByteArrayOutputStream();
            IOUtils.copy(resource.getContent(), bout);
            return bout.toByteArray();
        } catch (Throwable e) {
            throw new SQLException(e);
        } finally {
            IOUtils.closeQuietly(bout);
        }
    }

    private boolean isContentOverflow(byte[] content, String resPath) throws SQLException {
        if (kylinConfig.isJsonAlwaysSmallCell() && isJsonMetadata(resPath)) {

            int smallCellMetadataWarningThreshold = kylinConfig.getSmallCellMetadataWarningThreshold();
            int smallCellMetadataErrorThreshold = kylinConfig.getSmallCellMetadataErrorThreshold();

            if (content.length > smallCellMetadataWarningThreshold) {
                logger.warn(
                        "A JSON metadata entry's size is not supposed to exceed kylin.metadata.jdbc.small-cell-meta-size-warning-threshold("
                                + smallCellMetadataWarningThreshold + "), resPath: " + resPath + ", actual size: "
                                + content.length);
            }
            if (content.length > smallCellMetadataErrorThreshold) {
                throw new SQLException(new IllegalArgumentException(
                        "A JSON metadata entry's size is not supposed to exceed kylin.metadata.jdbc.small-cell-meta-size-error-threshold("
                                + smallCellMetadataErrorThreshold + "), resPath: " + resPath + ", actual size: "
                                + content.length));
            }

            return false;
        }

        int maxSize = kylinConfig.getJdbcResourceStoreMaxCellSize();
        if (content.length > maxSize)
            return true;
        else
            return false;
    }

    private void createTableIfNeeded(final String tableName) throws SQLException {
        executeSql(new SqlOperation() {
            @Override
            public void execute(Connection connection) throws SQLException {
                if (checkTableExists(tableName, connection)) {
                    logger.info("Table [{}] already exists", tableName);
                    return;
                }

                pstat = connection.prepareStatement(getCreateIfNeededSql(tableName));
                pstat.executeUpdate();
                logger.info("Create table [{}] success", tableName);
            }

            private boolean checkTableExists(final String tableName, final Connection connection) throws SQLException {
                final PreparedStatement ps = connection.prepareStatement(getCheckTableExistsSql(tableName));
                final ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    if (tableName.equals(rs.getString(1))) {
                        return true;
                    }
                }

                return false;
            }
        });
    }

    private void createIndex(final String indexName, final String tableName, final String colName) {
        try {
            executeSql(new SqlOperation() {
                @Override
                public void execute(Connection connection) throws SQLException {
                    pstat = connection.prepareStatement(getCreateIndexSql(indexName, tableName, colName));
                    pstat.executeUpdate();
                }
            });
        } catch (SQLException ex) {
            logger.info("Create index failed with message: " + ex.getLocalizedMessage());
        }
    }

    abstract static class SqlOperation {
        PreparedStatement pstat = null;
        ResultSet rs = null;

        abstract public void execute(final Connection connection) throws SQLException;
    }

    private void executeSql(SqlOperation operation) throws SQLException {
        Connection connection = null;
        try {
            connection = connectionManager.getConn();
            operation.execute(connection);
            queriedSqlNum++;
        } finally {
            JDBCConnectionManager.closeQuietly(operation.rs);
            JDBCConnectionManager.closeQuietly(operation.pstat);
            JDBCConnectionManager.closeQuietly(connection);
        }
    }

    private String getCheckTableExistsSql(final String tableName) {
        final String sql = MessageFormat.format(jdbcSqlQueryFormat.getCheckTableExistsSql(), tableName);
        return sql;
    }

    //sql queries
    private String getCreateIfNeededSql(String tableName) {
        String sql = MessageFormat.format(jdbcSqlQueryFormat.getCreateIfNeedSql(), tableName, META_TABLE_KEY,
                META_TABLE_TS, META_TABLE_CONTENT);
        return sql;
    }

    //sql queries
    private String getCreateIndexSql(String indexName, String tableName, String indexCol) {
        String sql = MessageFormat.format(jdbcSqlQueryFormat.getCreateIndexSql(), indexName, tableName, indexCol);
        return sql;
    }

    private String getKeyEqualSqlString(boolean fetchContent, boolean fetchTimestamp) {
        String sql = MessageFormat.format(jdbcSqlQueryFormat.getKeyEqualsSql(),
                getSelectList(fetchContent, fetchTimestamp), tableName, META_TABLE_KEY);
        return sql;
    }

    private String getDeletePstatSql() {
        String sql = MessageFormat.format(jdbcSqlQueryFormat.getDeletePstatSql(), tableName, META_TABLE_KEY);
        return sql;
    }

    private String getListResourceSqlString() {
        String sql = MessageFormat.format(jdbcSqlQueryFormat.getListResourceSql(), META_TABLE_KEY, tableName,
                META_TABLE_KEY);
        return sql;
    }

    private String getAllResourceSqlString() {
        String sql = MessageFormat.format(jdbcSqlQueryFormat.getAllResourceSql(), getSelectList(true, true), tableName,
                META_TABLE_KEY, META_TABLE_TS, META_TABLE_TS);
        return sql;
    }

    private String getReplaceSql() {
        String sql = MessageFormat.format(jdbcSqlQueryFormat.getReplaceSql(), tableName, META_TABLE_TS,
                META_TABLE_CONTENT, META_TABLE_KEY);
        return sql;
    }

    private String getInsertSql() {
        String sql = MessageFormat.format(jdbcSqlQueryFormat.getInsertSql(), tableName, META_TABLE_KEY, META_TABLE_TS,
                META_TABLE_CONTENT);
        return sql;
    }

    @SuppressWarnings("unused")
    private String getReplaceSqlWithoutContent() {
        String sql = MessageFormat.format(jdbcSqlQueryFormat.getReplaceSqlWithoutContent(), tableName, META_TABLE_TS,
                META_TABLE_KEY);
        return sql;
    }

    private String getInsertSqlWithoutContent() {
        String sql = MessageFormat.format(jdbcSqlQueryFormat.getInsertSqlWithoutContent(), tableName, META_TABLE_KEY,
                META_TABLE_TS);
        return sql;
    }

    private String getUpdateSqlWithoutContent() {
        String sql = MessageFormat.format(jdbcSqlQueryFormat.getUpdateSqlWithoutContent(), tableName, META_TABLE_TS,
                META_TABLE_KEY, META_TABLE_TS);
        return sql;
    }

    private String getUpdateContentSql() {
        String sql = MessageFormat.format(jdbcSqlQueryFormat.getUpdateContentSql(), tableName, META_TABLE_CONTENT,
                META_TABLE_KEY);
        return sql;
    }

    private String getSelectList(boolean fetchContent, boolean fetchTimestamp) {
        StringBuilder sb = new StringBuilder();
        sb.append(META_TABLE_KEY);
        if (fetchTimestamp)
            sb.append("," + META_TABLE_TS);
        if (fetchContent)
            sb.append("," + META_TABLE_CONTENT);
        return sb.toString();
    }

    private InputStream getInputStream(String resPath, ResultSet rs) throws SQLException, IOException {
        if (rs == null) {
            return null;
        }
        InputStream inputStream = rs.getBlob(META_TABLE_CONTENT) == null ? null
                : rs.getBlob(META_TABLE_CONTENT).getBinaryStream();
        if (inputStream != null) {
            return inputStream;
        } else {
            Path redirectPath = bigCellHDFSPath(resPath);
            FileSystem fileSystem = HadoopUtil.getFileSystem(redirectPath);
            return fileSystem.open(redirectPath);
        }
    }

    private Path writeLargeCellToHdfs(String resPath, byte[] largeColumn) throws SQLException {

        boolean isResourceExist;
        FSDataOutputStream out = null;
        Path redirectPath = bigCellHDFSPath(resPath);
        Path oldPath = new Path(redirectPath.toString() + "_old");
        FileSystem fileSystem = null;
        try {
            fileSystem = HadoopUtil.getFileSystem(redirectPath);
        } catch (IOException e) {
            e.printStackTrace();
        }
        try {
            isResourceExist = fileSystem.exists(redirectPath);
            if (isResourceExist) {
                FileUtil.copy(fileSystem, redirectPath, fileSystem, oldPath, false,
                        HadoopUtil.getCurrentConfiguration());
                fileSystem.delete(redirectPath, true);
                logger.debug("a copy of hdfs file {} is made", redirectPath);
            }
            out = fileSystem.create(redirectPath);
            out.write(largeColumn);
            return redirectPath;
        } catch (Throwable e) {
            try {
                rollbackLargeCellFromHdfs(resPath);
            } catch (Throwable ex) {
                logger.error("fail to roll back resource " + resPath + " in hdfs", ex);
            }
            throw new SQLException(e);
        } finally {
            IOUtils.closeQuietly(out);
        }
    }

    public void rollbackLargeCellFromHdfs(String resPath) throws SQLException {
        Path redirectPath = bigCellHDFSPath(resPath);
        Path oldPath = new Path(redirectPath.toString() + "_old");
        FileSystem fileSystem = null;
        try {
            fileSystem = HadoopUtil.getFileSystem(redirectPath);
        } catch (IOException e) {
            e.printStackTrace();
        }
        try {
            if (fileSystem.exists(oldPath)) {
                FileUtil.copy(fileSystem, oldPath, fileSystem, redirectPath, true, true,
                        HadoopUtil.getCurrentConfiguration());
                logger.info("roll back hdfs file {}", resPath);
            } else {
                fileSystem.delete(redirectPath, true);
                logger.warn("no backup for hdfs file {} is found, clean it", resPath);
            }
        } catch (Throwable e) {

            try {
                //last try to delete redirectPath, because we prefer a deleted rather than incomplete
                fileSystem.delete(redirectPath, true);
            } catch (Throwable ignore) {
                // ignore it
            }

            throw new SQLException(e);
        }
    }

    private void cleanOldLargeCellFromHdfs(String resPath) throws SQLException {
        Path redirectPath = bigCellHDFSPath(resPath);
        Path oldPath = new Path(redirectPath.toString() + "_old");
        FileSystem fileSystem = null;
        try {
            fileSystem = HadoopUtil.getFileSystem(redirectPath);
        } catch (IOException e) {
            e.printStackTrace();
        }
        try {
            if (fileSystem.exists(oldPath)) {
                fileSystem.delete(oldPath, true);
            }
        } catch (Throwable e) {
            logger.warn("error cleaning the backup file for " + redirectPath + ", leave it as garbage", e);
        }
    }

    public Path bigCellHDFSPath(String resPath) {
        String metastoreBigCellHdfsDirectory = this.kylinConfig.getMetastoreBigCellHdfsDirectory();
        Path redirectPath = new Path(metastoreBigCellHdfsDirectory, "resources-jdbc" + resPath);
        return redirectPath;
    }

    public long getQueriedSqlNum() {
        return queriedSqlNum;
    }

    public String getMetaTableName(String resPath) {
        if (resPath.startsWith(ResourceStore.BAD_QUERY_RESOURCE_ROOT) || resPath.startsWith(ResourceStore.CUBE_STATISTICS_ROOT)
                || resPath.startsWith(ResourceStore.DICT_RESOURCE_ROOT) || resPath.startsWith(ResourceStore.EXECUTE_RESOURCE_ROOT)
                || resPath.startsWith(ResourceStore.EXECUTE_OUTPUT_RESOURCE_ROOT) || resPath.startsWith(ResourceStore.EXT_SNAPSHOT_RESOURCE_ROOT)
                || resPath.startsWith(ResourceStore.TEMP_STATMENT_RESOURCE_ROOT)) {
            tableName = tablesName[1];
        } else {
            tableName = tablesName[0];
        }
        return tableName;
    }

}