package com.example.ringerguard;

import android.telecom.Call;
import android.telecom.CallScreeningService;

/**
 * 来电筛选服务（运营商来电“必响”的核心）。
 *
 * 当本应用持有“来电筛选”角色（ROLE_CALL_SCREENING）后，系统会在每次来电、
 * 在电话响起之前，把本服务拉起来调用一次 {@link #onScreenCall}，
 * 即使本应用此前没有任何进程在运行——只要没有被用户“强行停止”。
 *
 * 处理逻辑：
 * 1. 仅对“来电”执行一次 {@link AudioGuard#enforce}：退出静音/震动/勿扰，
 *    必要时（iQOO 零音量 / 流被 mute）恢复铃声，使这通来电从第一声就响；
 * 2. 始终放行来电（绝不拦截 / 不静音），等同“正常来电”。
 *
 * 因为只在来电瞬间运行、跑完即结束，平时没有任何常驻进程：
 * - 待机耗电 ≈ 0；
 * - 不存在“服务还在但功能假死”的问题（没有需要长期保活的进程）。
 */
public class CallGuardScreeningService extends CallScreeningService {

    @Override
    public void onScreenCall(Call.Details callDetails) {
        if (callDetails == null) {
            DiagLog.log(this, "[来电] onScreenCall 被系统调用，但 callDetails 为空");
            return;
        }

        /*
         * 只跳过“明确是去电”的情况；来电以及“方向未知”都执行恢复。
         *
         * 部分厂商 ROM 在来电时可能返回 DIRECTION_UNKNOWN 而非 DIRECTION_INCOMING，
         * 若只判断 == DIRECTION_INCOMING 会漏掉这类来电，导致这通电话不响。
         * 对去电执行恢复也无害，因此这里用“非去电即恢复”的保守策略。
         */
        boolean notOutgoing =
                callDetails.getCallDirection() != Call.Details.DIRECTION_OUTGOING;
        boolean enabled = AudioGuard.isEnabled(this);

        /*
         * 关键诊断点：只要系统在来电时把本服务拉起来，这条日志就一定会出现。
         * 重启后来电不响时，回主界面看日志：
         *   有这条  -> 服务被拉起了，问题在 enforce / 勿扰权限（根因 B）；
         *   没这条  -> 系统压根没拉起本服务（根因 A，需要保活）。
         */
        DiagLog.log(this, "[来电] onScreenCall 被系统调用 方向="
                + directionText(callDetails)
                + " 守护=" + (enabled ? "开" : "关"));

        if (notOutgoing && enabled) {
            try {
                AudioGuard.enforce(this, "来电");
            } catch (Exception e) {
                DiagLog.log(this, "[来电] enforce 异常：" + e.getMessage());
            }
        }

        respondAllow(callDetails);
    }

    private static String directionText(Call.Details details) {
        try {
            int direction = details.getCallDirection();
            if (direction == Call.Details.DIRECTION_INCOMING) {
                return "来电";
            }
            if (direction == Call.Details.DIRECTION_OUTGOING) {
                return "去电";
            }
            return "未知(" + direction + ")";
        } catch (Exception e) {
            return "未知";
        }
    }

    /**
     * 必须始终响应系统，且不做任何拦截 / 静音 / 跳过通知，否则可能影响正常接听。
     */
    private void respondAllow(Call.Details callDetails) {
        try {
            CallResponse response = new CallResponse.Builder()
                    .setDisallowCall(false)
                    .setRejectCall(false)
                    .setSilenceCall(false)
                    .setSkipCallLog(false)
                    .setSkipNotification(false)
                    .build();

            respondToCall(callDetails, response);
        } catch (Exception ignored) {
        }
    }
}
