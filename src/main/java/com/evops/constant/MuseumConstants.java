package com.evops.constant;

/**
 * 文物修复台账各业务表的状态与动作常量。
 * 库中一律存字符串码值，禁止用物理删除表达"撤销"。
 */
public final class MuseumConstants {

    private MuseumConstants() {
    }

    /** 文物档案 / 入藏登记状态 */
    public static final String ACTIVE = "ACTIVE";
    public static final String REVOKED = "REVOKED";

    /** 保存等级：一 / 二 / 三级 */
    public static final String GRADE_ONE = "ONE";
    public static final String GRADE_TWO = "TWO";
    public static final String GRADE_THREE = "THREE";

    /** 修复项目状态：立项 -> 修复中 -> 修复完成 -> 已验收（作废为旁路终态） */
    public static final String PROJECT_PLANNED = "PLANNED";
    public static final String PROJECT_REPAIRING = "REPAIRING";
    public static final String PROJECT_REPAIRED = "REPAIRED";
    public static final String PROJECT_ACCEPTED = "ACCEPTED";
    public static final String PROJECT_CANCELLED = "CANCELLED";

    /** 借还状态 */
    public static final String LOAN_LENT = "LENT";
    public static final String LOAN_RETURNED = "RETURNED";
    public static final String LOAN_OVERDUE = "OVERDUE";

    /** 验收结论 */
    public static final String ACCEPT_PASS = "PASS";
    public static final String ACCEPT_CONDITIONAL = "CONDITIONAL";

    /** 台账流水动作 */
    public static final String ACTION_INTAKE = "INTAKE";
    public static final String ACTION_LOAN_OUT = "LOAN_OUT";
    public static final String ACTION_RETURN = "RETURN";
    public static final String ACTION_REPAIR_START = "REPAIR_START";
    public static final String ACTION_REPAIR_STAGE = "REPAIR_STAGE";
    public static final String ACTION_REPAIR_FINISH = "REPAIR_FINISH";
    public static final String ACTION_ACCEPT = "ACCEPT";
    public static final String ACTION_REVOKE = "REVOKE";
    public static final String ACTION_CANCEL = "CANCEL";

    /** 起步幂等锁固定主键，全表只允许一行 */
    public static final int BOOTSTRAP_LOCK_ID = 1;
}
