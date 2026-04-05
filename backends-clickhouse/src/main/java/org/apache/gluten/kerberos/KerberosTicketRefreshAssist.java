/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.gluten.kerberos;

import org.apache.spark.SparkConf;
import org.apache.spark.network.util.JavaUtils;
import org.apache.spark.util.ThreadUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class KerberosTicketRefreshAssist {

  private static final Logger LOG = LoggerFactory.getLogger(KerberosTicketRefreshAssist.class);

  private static final String BACKEND_CONFIG_KEY =
      "spark.gluten.sql.columnar.backend.ch.runtime_config.";

  private static final String USE_KERBEROS_KEY =
      BACKEND_CONFIG_KEY + "hdfs.hadoop_security_authentication";
  private static final String KERBEROS_CACHE_KEY =
      BACKEND_CONFIG_KEY + "hdfs.hadoop_security_kerberos_ticket_cache_path";
  private static final String USE_KERBEROS_VALUE = "kerberos";
  private static final String SPARK_PRINCIPAL_KEY = "spark.kerberos.principal";
  private static final String SPARK_KEYTAB_KEY = "spark.kerberos.keytab";
  private static final String PRINCIPAL_KEY = BACKEND_CONFIG_KEY + "kerberos.principal";
  private static final String KEYTAB_KEY = BACKEND_CONFIG_KEY + "kerberos.keytab";
  private static final String KRB5_CONF_KEY = BACKEND_CONFIG_KEY + "kerberos.krb5.file";
  private static final String RELOGIN_INTERVAL_KEY = "spark.kerberos.relogin.period";
  private static final String RELOGIN_INTERVAL_DEFAULT = "60m";
  private static final String CACHE_NAME = "krb5cc_gluten";
  private static final String KRB5_CONF_NAME = "krb5.conf";
  private static KerberosTicketRefreshAssist INSTANCE;

  private final ScheduledExecutorService initExecutor;

  private KerberosTicketRefreshAssist() {
    initExecutor = ThreadUtils.newDaemonSingleThreadScheduledExecutor("CH Kerberos Renewal");
  }

  public static void initKerberosIfNeeded(SparkConf sparkConf) {
    if (!sparkConf.contains(USE_KERBEROS_KEY)
        || !sparkConf.get(USE_KERBEROS_KEY).equals(USE_KERBEROS_VALUE)) {
      LOG.info("Kerberos is not enabled, skip initialization");
      return;
    }
    if (!sparkConf.contains(PRINCIPAL_KEY) || !sparkConf.contains(KEYTAB_KEY)) {
      if ("cluster".equals(sparkConf.get("spark.submit.deployMode", "client"))) {
        LOG.error("Kerberos enabled with the cluster mode, but principal or keytab is not set");
        return;
      }
      if (!sparkConf.contains(SPARK_PRINCIPAL_KEY) || !sparkConf.contains(SPARK_KEYTAB_KEY)) {
        LOG.error("Kerberos enabled, but principal or keytab is not set");
        return;
      }
    }
    String workingDir = System.getProperty("user.dir");
    String principal = sparkConf.get(PRINCIPAL_KEY, sparkConf.get(SPARK_PRINCIPAL_KEY));
    String keytab = sparkConf.get(KEYTAB_KEY, sparkConf.get(SPARK_KEYTAB_KEY));
    String keytabName = new File(keytab).getName();
    String keytabPath = workingDir + "/" + keytabName;
    if (!new File(keytabPath).exists()) {
      LOG.warn(
          "keytab file "
              + keytabPath
              + " not exists in working dir, try to use spark.kerberos.keytab");
      keytabPath = keytab;
    }

    String cachePath = workingDir + "/" + CACHE_NAME;
    String krb5ConfPath = null;
    if (sparkConf.contains(KRB5_CONF_KEY)) {
      String krb5Conf = sparkConf.get(KRB5_CONF_KEY);
      String krb5ConfName = new File(krb5Conf).getName();
      krb5ConfPath = workingDir + "/" + krb5ConfName;
      if (!new File(krb5ConfPath).exists()) {
        LOG.warn("krb5 conf file not exists in working dir, try to use config krb5 conf");
        krb5ConfPath = krb5Conf;
      }
    } else {
      krb5ConfPath = workingDir + "/" + KRB5_CONF_NAME;
      if (!new File(krb5ConfPath).exists()) {
        LOG.warn("krb5 conf file not exists in working dir, try to use system krb5 conf");
        krb5ConfPath = null;
      }
    }

    KerberosInit kerberosInit;
    try {
      kerberosInit = new KerberosInit(principal, keytabPath, cachePath, krb5ConfPath);
    } catch (Exception e) {
      LOG.error("Failed to init kerberos", e);
      return;
    }
    String period = sparkConf.get(RELOGIN_INTERVAL_KEY, RELOGIN_INTERVAL_DEFAULT);
    long refreshSeconds = JavaUtils.timeStringAs(period, TimeUnit.SECONDS);

    KerberosTicketRefreshAssist assist = KerberosTicketRefreshAssist.getInstance();
    assist.initExecutor.scheduleAtFixedRate(
        () -> {
          try {
            kerberosInit.execute();
          } catch (Exception e) {
            LOG.error("Failed to renew kerberos ticket", e);
          }
        },
        0,
        refreshSeconds,
        TimeUnit.SECONDS);

    sparkConf.set(KERBEROS_CACHE_KEY, cachePath);
  }

  public static synchronized KerberosTicketRefreshAssist getInstance() {
    if (INSTANCE == null) {
      INSTANCE = new KerberosTicketRefreshAssist();
    }
    return INSTANCE;
  }

  public static void shutdownIfNeeded() {
    if (INSTANCE != null) {
      INSTANCE.initExecutor.shutdown();
    }
  }
}
