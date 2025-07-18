/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 * 
 * This file is generated by jOOQ.
 */
package edu.uci.ics.texera.dao.jooq.generated.tables.pojos;


import edu.uci.ics.texera.dao.jooq.generated.enums.PrivilegeEnum;
import edu.uci.ics.texera.dao.jooq.generated.tables.interfaces.IComputingUnitUserAccess;


/**
 * This class is generated by jOOQ.
 */
@SuppressWarnings({ "all", "unchecked", "rawtypes" })
public class ComputingUnitUserAccess implements IComputingUnitUserAccess {

    private static final long serialVersionUID = 1L;

    private Integer       cuid;
    private Integer       uid;
    private PrivilegeEnum privilege;

    public ComputingUnitUserAccess() {}

    public ComputingUnitUserAccess(IComputingUnitUserAccess value) {
        this.cuid = value.getCuid();
        this.uid = value.getUid();
        this.privilege = value.getPrivilege();
    }

    public ComputingUnitUserAccess(
        Integer       cuid,
        Integer       uid,
        PrivilegeEnum privilege
    ) {
        this.cuid = cuid;
        this.uid = uid;
        this.privilege = privilege;
    }

    /**
     * Getter for <code>texera_db.computing_unit_user_access.cuid</code>.
     */
    @Override
    public Integer getCuid() {
        return this.cuid;
    }

    /**
     * Setter for <code>texera_db.computing_unit_user_access.cuid</code>.
     */
    @Override
    public void setCuid(Integer cuid) {
        this.cuid = cuid;
    }

    /**
     * Getter for <code>texera_db.computing_unit_user_access.uid</code>.
     */
    @Override
    public Integer getUid() {
        return this.uid;
    }

    /**
     * Setter for <code>texera_db.computing_unit_user_access.uid</code>.
     */
    @Override
    public void setUid(Integer uid) {
        this.uid = uid;
    }

    /**
     * Getter for <code>texera_db.computing_unit_user_access.privilege</code>.
     */
    @Override
    public PrivilegeEnum getPrivilege() {
        return this.privilege;
    }

    /**
     * Setter for <code>texera_db.computing_unit_user_access.privilege</code>.
     */
    @Override
    public void setPrivilege(PrivilegeEnum privilege) {
        this.privilege = privilege;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("ComputingUnitUserAccess (");

        sb.append(cuid);
        sb.append(", ").append(uid);
        sb.append(", ").append(privilege);

        sb.append(")");
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // FROM and INTO
    // -------------------------------------------------------------------------

    @Override
    public void from(IComputingUnitUserAccess from) {
        setCuid(from.getCuid());
        setUid(from.getUid());
        setPrivilege(from.getPrivilege());
    }

    @Override
    public <E extends IComputingUnitUserAccess> E into(E into) {
        into.from(this);
        return into;
    }
}
