package com.alibaba.datax.plugin.writer.hivewriter;

import com.alibaba.datax.common.exception.DataXException;
import com.alibaba.datax.common.plugin.RecordReceiver;
import com.alibaba.datax.common.spi.Writer;
import com.alibaba.datax.common.util.Configuration;
import com.google.common.collect.Sets;
import org.apache.commons.io.Charsets;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Validate;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.security.UserGroupInformation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import parquet.schema.MessageTypeParser;
import sun.security.krb5.Config;

import java.io.IOException;
import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.*;

/**
 * @description:HiveWriter
 * @author: caoes
 * create:2022/7/18 17:04
 **/
public class HiveWriter extends Writer {
    public static class Job extends Writer.Job {
        private static final Logger LOG = LoggerFactory.getLogger(Job.class);

        private Configuration writerSliceConfig = null;

        private String defaultFS;
        private String path;
        private String fileType;
        private String fileName;
        private List<Configuration> columns;
        private String writeMode;
        private String fieldDelimiter;
        private String compress;
        private String encoding;
        private HashSet<String> tmpFiles = new HashSet<String>();//临时文件全路径
        private HashSet<String> endFiles = new HashSet<String>();//最终文件全路径
        private String databaseName;
        private String tableName;
        private String partition;
        private String jdbcUrl;
        private String user;
        private String password;
        private String other;
        private Boolean haveKerberos = false;
        private String principal;
        private String  kerberosKeytabFilePath;
        private String  kerberosPrincipal;
        private String  krb5FilePath;
        private HiveHelper hiveHelper = null;

        @Override
        public void init() {
            this.writerSliceConfig = this.getPluginJobConf();
            this.validateParameter();

            //创建textfile存储
            hiveHelper = new HiveHelper();

            hiveHelper.getFileSystem(defaultFS, this.writerSliceConfig);
        }

        private void validateParameter() {
            this.defaultFS = this.writerSliceConfig.getNecessaryValue(Key.DEFAULT_FS, HiveWriterErrorCode.REQUIRED_VALUE);
            this.databaseName = this.writerSliceConfig.getNecessaryValue(Key.DATABASE_NAME, HiveWriterErrorCode.REQUIRED_VALUE);
            this.tableName = this.writerSliceConfig.getNecessaryValue(Key.TABLE_NAME, HiveWriterErrorCode.REQUIRED_VALUE);
            this.jdbcUrl = this.writerSliceConfig.getNecessaryValue(Key.JDBC_URL, HiveWriterErrorCode.REQUIRED_VALUE);
            this.user = this.writerSliceConfig.getNecessaryValue(Key.JDBC_USER, HiveWriterErrorCode.REQUIRED_VALUE);
            this.password = this.writerSliceConfig.getString(Key.JDBC_PASSWORD,"");
            this.other = this.writerSliceConfig.getString(Key.JDBC_OTHER,"");
            this.haveKerberos = this.writerSliceConfig.getBool(Key.HAVE_KERBEROS, false);
            if(this.haveKerberos){
                this.principal = this.writerSliceConfig.getString(Key.PRINCIPAL);
                this.krb5FilePath = this.writerSliceConfig.getString(Key.KERBEROS_KRB5_FILE_PATH);
                this.kerberosKeytabFilePath = this.writerSliceConfig.getString(Key.KERBEROS_KEYTAB_FILE_PATH);
                this.kerberosPrincipal = this.writerSliceConfig.getString(Key.KERBEROS_PRINCIPAL);
            }
            this.partition = this.writerSliceConfig.getString(Key.PARTITION);
            //fileType check
            this.fileType = this.writerSliceConfig.getNecessaryValue(Key.FILE_TYPE, HiveWriterErrorCode.REQUIRED_VALUE);
            if( !fileType.equalsIgnoreCase("ORC") && !fileType.equalsIgnoreCase("TEXT")){
                String message = "HdfsWriter插件目前只支持ORC和TEXT两种格式的文件,请将filetype选项的值配置为ORC或者TEXT";
                throw DataXException.asDataXException(HiveWriterErrorCode.ILLEGAL_VALUE, message);
            }
            //path
            this.path = this.writerSliceConfig.getNecessaryValue(Key.PATH, HiveWriterErrorCode.REQUIRED_VALUE);
            if(!path.startsWith("/")){
                String message = String.format("请检查参数path:[%s],需要配置为绝对路径", path);
                LOG.error(message);
                throw DataXException.asDataXException(HiveWriterErrorCode.ILLEGAL_VALUE, message);
            }else if(path.contains("*") || path.contains("?")){
                String message = String.format("请检查参数path:[%s],不能包含*,?等特殊字符", path);
                LOG.error(message);
                throw DataXException.asDataXException(HiveWriterErrorCode.ILLEGAL_VALUE, message);
            }
            //fileName
            this.fileName = this.writerSliceConfig.getNecessaryValue(Key.FILE_NAME, HiveWriterErrorCode.REQUIRED_VALUE);
            //columns check
            this.columns = this.writerSliceConfig.getListConfiguration(Key.COLUMN);
            if (null == columns || columns.size() == 0) {
                throw DataXException.asDataXException(HiveWriterErrorCode.REQUIRED_VALUE, "您需要指定 columns");
            }else{
                for (Configuration eachColumnConf : columns) {
                    eachColumnConf.getNecessaryValue(Key.NAME, HiveWriterErrorCode.COLUMN_REQUIRED_VALUE);
                    eachColumnConf.getNecessaryValue(Key.TYPE, HiveWriterErrorCode.COLUMN_REQUIRED_VALUE);
                }
            }
            //writeMode check
            this.writeMode = this.writerSliceConfig.getNecessaryValue(Key.WRITE_MODE, HiveWriterErrorCode.REQUIRED_VALUE);
            writeMode = writeMode.toLowerCase().trim();
            Set<String> supportedWriteModes = Sets.newHashSet("append", "nonconflict", "truncate");
            if (!supportedWriteModes.contains(writeMode)) {
                throw DataXException.asDataXException(HiveWriterErrorCode.ILLEGAL_VALUE,
                        String.format("仅支持append, nonConflict, truncate三种模式, 不支持您配置的 writeMode 模式 : [%s]",
                                writeMode));
            }
            this.writerSliceConfig.set(Key.WRITE_MODE, writeMode);
            //fieldDelimiter check
            this.fieldDelimiter = this.writerSliceConfig.getString(Key.FIELD_DELIMITER,null);
            if(null == fieldDelimiter){
                throw DataXException.asDataXException(HiveWriterErrorCode.REQUIRED_VALUE,
                        String.format("您提供配置文件有误，[%s]是必填参数.", Key.FIELD_DELIMITER));
            }else if(1 != fieldDelimiter.length()){
                // warn: if have, length must be one
                throw DataXException.asDataXException(HiveWriterErrorCode.ILLEGAL_VALUE,
                        String.format("仅仅支持单字符切分, 您配置的切分为 : [%s]", fieldDelimiter));
            }
            //compress check
            this.compress  = this.writerSliceConfig.getString(Key.COMPRESS,null);
            if(fileType.equalsIgnoreCase("TEXT")){
                Set<String> textSupportedCompress = Sets.newHashSet("GZIP", "BZIP2");
                //用户可能配置的是compress:"",空字符串,需要将compress设置为null
                if(StringUtils.isBlank(compress) ){
                    this.writerSliceConfig.set(Key.COMPRESS, null);
                }else {
                    compress = compress.toUpperCase().trim();
                    if(!textSupportedCompress.contains(compress) ){
                        throw DataXException.asDataXException(HiveWriterErrorCode.ILLEGAL_VALUE,
                                String.format("目前TEXT FILE仅支持GZIP、BZIP2 两种压缩, 不支持您配置的 compress 模式 : [%s]",
                                        compress));
                    }
                }
            }else if(fileType.equalsIgnoreCase("ORC")){
                Set<String> orcSupportedCompress = Sets.newHashSet("NONE", "SNAPPY");
                if(null == compress){
                    this.writerSliceConfig.set(Key.COMPRESS, "NONE");
                }else {
                    compress = compress.toUpperCase().trim();
                    if(!orcSupportedCompress.contains(compress)){
                        throw DataXException.asDataXException(HiveWriterErrorCode.ILLEGAL_VALUE,
                                String.format("目前ORC FILE仅支持SNAPPY压缩, 不支持您配置的 compress 模式 : [%s]",
                                        compress));
                    }
                }

            }
            //Kerberos check
            Boolean haveKerberos = this.writerSliceConfig.getBool(Key.HAVE_KERBEROS, false);
            if(haveKerberos) {
                this.writerSliceConfig.getNecessaryValue(Key.KERBEROS_KEYTAB_FILE_PATH, HiveWriterErrorCode.REQUIRED_VALUE);
                this.writerSliceConfig.getNecessaryValue(Key.KERBEROS_PRINCIPAL, HiveWriterErrorCode.REQUIRED_VALUE);
            }
            // encoding check
            this.encoding = this.writerSliceConfig.getString(Key.ENCODING, Constant.DEFAULT_ENCODING);
            try {
                encoding = encoding.trim();
                this.writerSliceConfig.set(Key.ENCODING, encoding);
                Charsets.toCharset(encoding);
            } catch (Exception e) {
                throw DataXException.asDataXException(HiveWriterErrorCode.ILLEGAL_VALUE,
                        String.format("不支持您配置的编码格式:[%s]", encoding), e);
            }
        }

        @Override
        public void prepare() {
            LOG.info(String.format("数据库[%s]下表[%s] 存在分区[%s], 开始检查分区工作, 若分区不存在, 则会自动创建分区.", new Object[] { this.databaseName, this.tableName, this.partition }));
            if ((null != this.partition) && (!"".equals(this.partition))) {
                Connection connection = null;
                PreparedStatement pstmt = null;
                org.apache.hadoop.conf.Configuration hadoopConf;
                UserGroupInformation ugi;
                Boolean flag ;
                try
                {
                    if (StringUtils.isNotBlank(principal)) {
                        this.jdbcUrl += ";principal=" + filterPrincipal(this.principal);
                    }
                    String otherParams = filterOther(this.other);
                    if (StringUtils.isNotEmpty(otherParams) && !"?".equals(otherParams.substring(0, 1))) {
                        jdbcUrl += ";";
                    }
                    jdbcUrl += otherParams;
                    Class.forName(Constant.ORG_APACHE_HIVE_JDBC_HIVE_DRIVER);

                    if (this.haveKerberos) {
                        checkKerberosEnv(this.krb5FilePath);

                        hadoopConf = new org.apache.hadoop.conf.Configuration();

                        try {
                            ugi = createUGI(hadoopConf, this.kerberosPrincipal, this.kerberosKeytabFilePath, this.krb5FilePath, this.user);
                        } catch (Exception e) {
                            String message = String.format("kerberos认证失败,请确定kerberosKeytabFilePath[%s]和kerberosPrincipal[%s]填写正确",
                                    kerberosKeytabFilePath, kerberosPrincipal);
                            LOG.error(message);
                            throw DataXException.asDataXException(HiveWriterErrorCode.KERBEROS_LOGIN_ERROR, e);
                        }
                    }

                    connection = DriverManager.getConnection(jdbcUrl, user , password);
                    String partitionSql = "alter table " + this.databaseName + "." + this.tableName + " add if not exists partition("+ this.partition + ")";
                    LOG.info(String.format("开始创建分区, SQL: %s", new Object[] { partitionSql }));

                    pstmt = connection.prepareStatement(partitionSql);
                    flag = pstmt.execute();

                    LOG.info(String.format("开始创建分区结果状态, status: [%s]", new Object[] { flag }));
                } catch (Exception e) {
                    LOG.error(String.format("数据库[%s]下表[%s] 存在分区[%s], 创建新分区失败, 请检查是否存在分区字段.", new Object[] { this.databaseName, this.tableName, this.partition }));
                }

            }
            //若路径已经存在，检查path是否是目录
            if(hiveHelper.isPathexists(path)){
                if(!hiveHelper.isPathDir(path)){
                    throw DataXException.asDataXException(HiveWriterErrorCode.ILLEGAL_VALUE,
                            String.format("您配置的path: [%s] 不是一个合法的目录, 请您注意文件重名, 不合法目录名等情况.",
                                    path));
                }
                //根据writeMode对目录下文件进行处理
                Path[] existFilePaths = hiveHelper.hdfsDirList(path,fileName);
                boolean isExistFile = false;
                if(existFilePaths.length > 0){
                    isExistFile = true;
                }
                /**
                 if ("truncate".equals(writeMode) && isExistFile ) {
                 LOG.info(String.format("由于您配置了writeMode truncate, 开始清理 [%s] 下面以 [%s] 开头的内容",
                 path, fileName));
                 HiveHelper.deleteFiles(existFilePaths);
                 } else
                 */
                if ("append".equalsIgnoreCase(writeMode)) {
                    LOG.info(String.format("由于您配置了writeMode append, 写入前不做清理工作, [%s] 目录下写入相应文件名前缀  [%s] 的文件",
                            path, fileName));
                } else if ("nonconflict".equalsIgnoreCase(writeMode) && isExistFile) {
                    LOG.info(String.format("由于您配置了writeMode nonConflict, 开始检查 [%s] 下面的内容", path));
                    List<String> allFiles = new ArrayList<String>();
                    for (Path eachFile : existFilePaths) {
                        allFiles.add(eachFile.toString());
                    }
                    LOG.error(String.format("冲突文件列表为: [%s]", StringUtils.join(allFiles, ",")));
                    throw DataXException.asDataXException(HiveWriterErrorCode.ILLEGAL_VALUE,
                            String.format("由于您配置了writeMode nonConflict,但您配置的path: [%s] 目录不为空, 下面存在其他文件或文件夹.", path));
                }else if ("truncate".equalsIgnoreCase(writeMode) && isExistFile) {
                    LOG.info(String.format("由于您配置了writeMode truncate,  [%s] 下面的内容将被覆盖重写", path));
                    hiveHelper.deleteFiles(existFilePaths);
                }
            }else{
                throw DataXException.asDataXException(HiveWriterErrorCode.ILLEGAL_VALUE,
                        String.format("您配置的path: [%s] 不存在, 请先在hive端创建对应的数据库和表.", path));
            }
        }

        @Override
        public void post() {
            hiveHelper.renameFile(tmpFiles, endFiles);
            analyseTable();
        }

        @Override
        public void destroy() {
            hiveHelper.closeFileSystem();
        }

        @Override
        public List<Configuration> split(int mandatoryNumber) {
            LOG.info("begin do split...");
            List<Configuration> writerSplitConfigs = new ArrayList<Configuration>();
            String filePrefix = fileName;

            Set<String> allFiles = new HashSet<String>();

            //获取该路径下的所有已有文件列表
            if(hiveHelper.isPathexists(path)){
                allFiles.addAll(Arrays.asList(hiveHelper.hdfsDirList(path)));
            }

            String fileSuffix;
            //临时存放路径
            String storePath =  buildTmpFilePath(this.path);
            //最终存放路径
            String endStorePath = buildFilePath();
            this.path = endStorePath;
            for (int i = 0; i < mandatoryNumber; i++) {
                // handle same file name

                Configuration splitedTaskConfig = this.writerSliceConfig.clone();
                String fullFileName = null;
                String endFullFileName = null;

                fileSuffix = UUID.randomUUID().toString().replace('-', '_');

                fullFileName = String.format("%s%s%s__%s", defaultFS, storePath, filePrefix, fileSuffix);
                endFullFileName = String.format("%s%s%s__%s", defaultFS, endStorePath, filePrefix, fileSuffix);

                while (allFiles.contains(endFullFileName)) {
                    fileSuffix = UUID.randomUUID().toString().replace('-', '_');
                    fullFileName = String.format("%s%s%s__%s", defaultFS, storePath, filePrefix, fileSuffix);
                    endFullFileName = String.format("%s%s%s__%s", defaultFS, endStorePath, filePrefix, fileSuffix);
                }
                allFiles.add(endFullFileName);

                //设置临时文件全路径和最终文件全路径
                if("GZIP".equalsIgnoreCase(this.compress)){
                    this.tmpFiles.add(fullFileName + ".gz");
                    this.endFiles.add(endFullFileName + ".gz");
                }else if("BZIP2".equalsIgnoreCase(compress)){
                    this.tmpFiles.add(fullFileName + ".bz2");
                    this.endFiles.add(endFullFileName + ".bz2");
                }else{
                    this.tmpFiles.add(fullFileName);
                    this.endFiles.add(endFullFileName);
                }

                splitedTaskConfig
                        .set(com.alibaba.datax.plugin.unstructuredstorage.writer.Key.FILE_NAME,
                                fullFileName);

                LOG.info(String.format("splited write file name:[%s]",
                        fullFileName));

                writerSplitConfigs.add(splitedTaskConfig);
            }
            LOG.info("end do split.");
            return writerSplitConfigs;
        }

        private String buildFilePath() {
            boolean isEndWithSeparator = false;
            switch (IOUtils.DIR_SEPARATOR) {
                case IOUtils.DIR_SEPARATOR_UNIX:
                    isEndWithSeparator = this.path.endsWith(String
                            .valueOf(IOUtils.DIR_SEPARATOR));
                    break;
                case IOUtils.DIR_SEPARATOR_WINDOWS:
                    isEndWithSeparator = this.path.endsWith(String
                            .valueOf(IOUtils.DIR_SEPARATOR_WINDOWS));
                    break;
                default:
                    break;
            }
            if (!isEndWithSeparator) {
                this.path = this.path + IOUtils.DIR_SEPARATOR;
            }
            return this.path;
        }

        /**
         * 创建临时目录
         * @param userPath
         * @return
         */
        private String buildTmpFilePath(String userPath) {
            String tmpFilePath;
            boolean isEndWithSeparator = false;
            switch (IOUtils.DIR_SEPARATOR) {
                case IOUtils.DIR_SEPARATOR_UNIX:
                    isEndWithSeparator = userPath.endsWith(String
                            .valueOf(IOUtils.DIR_SEPARATOR));
                    break;
                case IOUtils.DIR_SEPARATOR_WINDOWS:
                    isEndWithSeparator = userPath.endsWith(String
                            .valueOf(IOUtils.DIR_SEPARATOR_WINDOWS));
                    break;
                default:
                    break;
            }
            String tmpSuffix;
            tmpSuffix = UUID.randomUUID().toString().replace('-', '_');
            if (!isEndWithSeparator) {
                tmpFilePath = String.format("%s__%s%s", userPath, tmpSuffix, IOUtils.DIR_SEPARATOR);
            }else if("/".equals(userPath)){
                tmpFilePath = String.format("%s__%s%s", userPath, tmpSuffix, IOUtils.DIR_SEPARATOR);
            }else{
                tmpFilePath = String.format("%s__%s%s", userPath.substring(0,userPath.length()-1), tmpSuffix, IOUtils.DIR_SEPARATOR);
            }
            while(hiveHelper.isPathexists(tmpFilePath)){
                tmpSuffix = UUID.randomUUID().toString().replace('-', '_');
                if (!isEndWithSeparator) {
                    tmpFilePath = String.format("%s__%s%s", userPath, tmpSuffix, IOUtils.DIR_SEPARATOR);
                }else if("/".equals(userPath)){
                    tmpFilePath = String.format("%s__%s%s", userPath, tmpSuffix, IOUtils.DIR_SEPARATOR);
                }else{
                    tmpFilePath = String.format("%s__%s%s", userPath.substring(0,userPath.length()-1), tmpSuffix, IOUtils.DIR_SEPARATOR);
                }
            }
            return tmpFilePath;
        }
        public void unitizeParquetConfig(Configuration writerSliceConfig) {
            String parquetSchema = writerSliceConfig.getString(Key.PARQUET_SCHEMA);
            if (StringUtils.isNotBlank(parquetSchema)) {
                LOG.info("parquetSchema has config. use parquetSchema:\n{}", parquetSchema);
                return;
            }

            List<Configuration> columns = writerSliceConfig.getListConfiguration(Key.COLUMN);
            if (columns == null || columns.isEmpty()) {
                throw DataXException.asDataXException("parquetSchema or column can't be blank!");
            }

            parquetSchema = generateParquetSchemaFromColumn(columns);
            // 为了兼容历史逻辑,对之前的逻辑做保留，但是如果配置的时候报错，则走新逻辑
            try {
                MessageTypeParser.parseMessageType(parquetSchema);
            } catch (Throwable e) {
                LOG.warn("The generated parquetSchema {} is illegal, try to generate parquetSchema in another way", parquetSchema);
                parquetSchema = HiveHelper.generateParquetSchemaFromColumnAndType(columns);
                LOG.info("The last generated parquet schema is {}", parquetSchema);
            }
            writerSliceConfig.set(Key.PARQUET_SCHEMA, parquetSchema);
            LOG.info("dataxParquetMode use default fields.");
            writerSliceConfig.set(Key.DATAX_PARQUET_MODE, "fields");
        }

        private String generateParquetSchemaFromColumn(List<Configuration> columns) {
            StringBuffer parquetSchemaStringBuffer = new StringBuffer();
            parquetSchemaStringBuffer.append("message m {");
            for (Configuration column: columns) {
                String name = column.getString("name");
                Validate.notNull(name, "column.name can't be null");

                String type = column.getString("type");
                Validate.notNull(type, "column.type can't be null");

                String parquetColumn = String.format("optional %s %s;", type, name);
                parquetSchemaStringBuffer.append(parquetColumn);
            }
            parquetSchemaStringBuffer.append("}");
            String parquetSchema = parquetSchemaStringBuffer.toString();
            LOG.info("generate parquetSchema:\n{}", parquetSchema);
            return parquetSchema;
        }

        /**
         * analyse table
         */
        private void analyseTable() {
            Connection connection = null;
            PreparedStatement pstmt = null;
            org.apache.hadoop.conf.Configuration hadoopConf;
            UserGroupInformation ugi;
            int flag ;
            try {
                if (null == this.partition || "".equals(this.partition)) {
                    if (StringUtils.isNotBlank(principal)) {
                        this.jdbcUrl += ";principal=" + filterPrincipal(this.principal);
                    }
                    String otherParams = filterOther(this.other);
                    if (StringUtils.isNotEmpty(otherParams) && !"?".equals(otherParams.substring(0, 1))) {
                        jdbcUrl += ";";
                    }
                    jdbcUrl += otherParams;
                }
                Class.forName(Constant.ORG_APACHE_HIVE_JDBC_HIVE_DRIVER);

                if (this.haveKerberos) {
                    checkKerberosEnv(this.krb5FilePath);
                    hadoopConf = new org.apache.hadoop.conf.Configuration();

                    try {
                        ugi = createUGI(hadoopConf, this.kerberosPrincipal, this.kerberosKeytabFilePath, this.krb5FilePath, this.user);
                    } catch (Exception e) {
                        String message = String.format("kerberos认证失败,请确定kerberosKeytabFilePath[%s]和kerberosPrincipal[%s]填写正确",
                                kerberosKeytabFilePath, kerberosPrincipal);
                        LOG.error(message);
                        throw DataXException.asDataXException(HiveWriterErrorCode.KERBEROS_LOGIN_ERROR, e);
                    }
                }

                connection = DriverManager.getConnection(jdbcUrl, user , password);
                String analyzeSql = "";
                if (StringUtils.isNotBlank(this.partition)) {
                    analyzeSql = "analyze table " + this.databaseName + "." + this.tableName + " partition(" + this.partition + ") COMPUTE STATISTICS ";
                } else {
                    analyzeSql = "analyze table " + this.databaseName + "." + this.tableName + " COMPUTE STATISTICS ";
                }
                LOG.info(String.format("分析数据表SQL: [%s]", new Object[] { analyzeSql }));

                pstmt = connection.prepareStatement(analyzeSql);
                flag = pstmt.executeUpdate();
                LOG.info(String.format("开始分析数据表结果状态, status: [%s]", new Object[] { flag }));
            } catch (Exception e) {
                LOG.error(String.format("开始分析数据表结果失败, status: [%s]", e));
            } finally {
                ugi = null;
                if (pstmt != null){
                    try {
                        pstmt.close();
                    } catch (SQLException e) {
                        LOG.error("close prepared statement error : {}",e.getMessage(),e);
                    }
                }

                if (connection != null){
                    try {
                        connection.close();
                    } catch (SQLException e) {
                        LOG.error("close connection error : {}",e.getMessage(),e);
                    }
                }
            }
        }
    }



    public static class Task extends Writer.Task {
        private static final Logger LOG = LoggerFactory.getLogger(Task.class);

        private Configuration writerSliceConfig;

        private String defaultFS;
        private String fileType;
        private String fileName;
        private HiveHelper hiveHelper = null;

        @Override
        public void init() {
            this.writerSliceConfig = this.getPluginJobConf();

            this.defaultFS = this.writerSliceConfig.getString(Key.DEFAULT_FS);
            this.fileType = this.writerSliceConfig.getString(Key.FILE_TYPE);
            //得当的已经是绝对路径，eg：hdfs://10.101.204.12:9000/user/hive/warehouse/writer.db/text/test.textfile
            this.fileName = this.writerSliceConfig.getString(Key.FILE_NAME);
            hiveHelper = new HiveHelper();
            hiveHelper.getFileSystem(defaultFS, writerSliceConfig);
        }

        @Override
        public void prepare() {

        }

        @Override
        public void startWrite(RecordReceiver lineReceiver) {
            LOG.info("begin do write...");
            LOG.info(String.format("write to file : [%s]", this.fileName));
            if(fileType.equalsIgnoreCase("TEXT")){
                //写TEXT FILE
                hiveHelper.textFileStartWrite(lineReceiver,this.writerSliceConfig, this.fileName,
                        this.getTaskPluginCollector(), this.fileType);
            }else if(fileType.equalsIgnoreCase("ORC")){
                //写ORC FILE
                hiveHelper.orcFileStartWrite(lineReceiver,this.writerSliceConfig, this.fileName,
                        this.getTaskPluginCollector(), this.fileType);
            }

            LOG.info("end do write");
        }

        @Override
        public void post() {

        }

        @Override
        public void destroy() {

        }

    }
    private static UserGroupInformation createUGI(org.apache.hadoop.conf.Configuration config, String principal, String keyTab, String krb5File, String user) throws IOException {
        Objects.requireNonNull(keyTab);
        if (StringUtils.isNotBlank(krb5File)) {
            System.setProperty(Key.JAVA_SECURITY_KRB5_CONF, krb5File);
        }
        config.set("hadoop.security.authentication", "kerberos");
        UserGroupInformation.setConfiguration(config);
        UserGroupInformation.loginUserFromKeytab(principal.trim(), keyTab.trim());
        UserGroupInformation.getCurrentUser();
        return UserGroupInformation.createRemoteUser(user);
    }

    private static void checkKerberosEnv( String krb5FilePath) {
        System.setProperty(Key.JAVA_SECURITY_KRB5_CONF, krb5FilePath);
        try {
            Config.refresh();
            Class<?> kerberosName = Class.forName("org.apache.hadoop.security.authentication.util.KerberosName");
            Field field = kerberosName.getDeclaredField("defaultRealm");
            field.setAccessible(true);
            field.set(null, Config.getInstance().getDefaultRealm());
        } catch (Exception e) {
            throw new RuntimeException("Update Kerberos environment failed.", e);
        }
    }
    private static String filterOther(String otherParams) {
        if (StringUtils.isBlank(otherParams)) {
            return "";
        }

        StringBuilder hiveConfListSb = new StringBuilder();
        hiveConfListSb.append("?");
        StringBuilder sessionVarListSb = new StringBuilder();

        String[] otherArray = otherParams.split(";", -1);

        for (String conf : otherArray) {
            sessionVarListSb.append(conf).append(";");
        }

        // remove the last ";"
        if (sessionVarListSb.length() > 0) {
            sessionVarListSb.deleteCharAt(sessionVarListSb.length() - 1);
        }

        if (hiveConfListSb.length() > 0) {
            hiveConfListSb.deleteCharAt(hiveConfListSb.length() - 1);
        }

        return sessionVarListSb.toString() + hiveConfListSb.toString();
    }

    private static String filterPrincipal(String principal) {
        if (StringUtils.isBlank(principal)) {
            return "";
        }

        StringBuilder hiveConfListSb = new StringBuilder();
        hiveConfListSb.append("?");
        StringBuilder sessionVarListSb = new StringBuilder();

        String[] otherArray = principal.split(";", -1);

        for (String conf : otherArray) {
            sessionVarListSb.append(conf).append(";");
        }

        // remove the last ";"
        if (sessionVarListSb.length() > 0) {
            sessionVarListSb.deleteCharAt(sessionVarListSb.length() - 1);
        }

        if (hiveConfListSb.length() > 0) {
            hiveConfListSb.deleteCharAt(hiveConfListSb.length() - 1);
        }

        return sessionVarListSb.toString() + hiveConfListSb.toString();
    }
}
