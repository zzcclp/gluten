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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sun.security.krb5.KrbAsReqBuilder;
import sun.security.krb5.KrbException;
import sun.security.krb5.PrincipalName;
import sun.security.krb5.RealmException;
import sun.security.krb5.internal.HostAddresses;
import sun.security.krb5.internal.KDCOptions;
import sun.security.krb5.internal.ccache.Credentials;
import sun.security.krb5.internal.ccache.CredentialsCache;

import javax.security.auth.kerberos.KeyTab;

import java.io.File;
import java.io.IOException;

public class KerberosInit {

  private static final Logger LOG = LoggerFactory.getLogger(KerberosInit.class);
  private final PrincipalName principal;
  private final KeyTab keytab;
  private final String cachePath;
  private final String krb5ConfPath;

  public KerberosInit(String prc, String keytabPath, String cachePath, String krb5ConfPath)
      throws RealmException {
    this.principal = new PrincipalName(prc);
    if (keytabPath == null || keytabPath.length() == 0) {
      LOG.warn("keytab path is null or empty, try use default keytab path");
      this.keytab = KeyTab.getInstance();
    } else {
      File keytabFile = new File(keytabPath);
      if (!keytabFile.exists()) {
        throw new IllegalArgumentException("keytab file not exists: " + keytabPath);
      }
      this.keytab = KeyTab.getInstance(keytabFile);
    }
    this.cachePath = cachePath;
    this.krb5ConfPath = krb5ConfPath;
    if (krb5ConfPath != null && !new File(krb5ConfPath).exists()) {
      throw new IllegalArgumentException("krb5 conf file not exists: " + krb5ConfPath);
    }
  }

  public KerberosInit(String prc, String keytabPath, String cachePath) throws RealmException {
    this(prc, keytabPath, cachePath, null);
  }

  private void execute0() throws KrbException, IOException {
    KrbAsReqBuilder builder = new KrbAsReqBuilder(principal, keytab);
    KDCOptions opt = new KDCOptions();
    builder.setOptions(opt);
    String realm = principal.getRealmString();
    PrincipalName sname = PrincipalName.tgsService(realm, realm);
    builder.setTarget(sname);
    builder.setAddresses(HostAddresses.getLocalAddresses());

    builder.action();
    Credentials credentials = builder.getCCreds();
    builder.destroy();
    CredentialsCache cache = CredentialsCache.create(principal, cachePath);
    if (cache == null) {
      throw new IOException("Unable to create the cache file " + cachePath);
    }
    cache.update(credentials);
    cache.save();
    LOG.info("Successfully stored kerberos ticket cache in " + cachePath);
  }

  public void execute() throws KrbException, IOException {
    String originalKrb5Conf = System.getProperty("java.security.krb5.conf");
    try {
      if (krb5ConfPath != null) {
        System.setProperty("java.security.krb5.conf", krb5ConfPath);
      }
      execute0();
    } finally {
      if (krb5ConfPath != null && originalKrb5Conf != null) {
        System.setProperty("java.security.krb5.conf", originalKrb5Conf);
      }
    }
  }
}
