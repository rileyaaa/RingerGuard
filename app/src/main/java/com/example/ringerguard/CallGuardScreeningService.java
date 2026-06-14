package com.example.ringerguard;

import android.telecom.Call;
import android.telecom.CallScreeningService;

/**
 * 来电筛选服务（来电自动恢复响铃的核心）。
 *
 * 本应用持有“来电筛选”角色（ROLE_CALL_SCREENING）后，系统会在每次来电、
 * 在电话响起之前，把本服务拉起来调用一次 {@link #onScreenCall}，
 * 即使本应用此前没有任何进程在运行——只要没有被用户“强行停止”。
 *
 * 关键（通讯录来电自动响铃）：
 * 系统默认只把“不在通讯录中的号码”交给本服务筛选；只有在用户授予
 * {@code READ_CONTACTS} 权限后，通讯录联系人来电才会同样触发
 * {@link #onScreenCall}。因此本应用申请该权限，仅用于让系统在通讯录来电时
 * 也唤起本服务，App 自身不读取、不遍历、不上传任何联系人数据。
 *
 * 处理逻辑：
 * 1. 仅对“来电”执行一次 {@link AudioGuard#enforce}：退出静音/震动/勿扰，
 *    必要时（零音量 / 流被 mute）恢复铃声，使这通来电从第一声就响；
 * 2. 始终放行来电（绝不拦截 / 不静音），等同“正常来电”。
 *
 * 因为只在来电瞬间运行、跑完即结束，平时没有任何常驻进程：
 * - 待机耗电接近 0；
 * - 不存在“服务还在但功能假死”的问题（没有需要长期保活的进程）。
 */
public class CallGuardScreeningService extends CallScreeningService {

    @Override
    public void onScreenCall(Call.Details callDetails) {
        if (callDetails == null) {
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

        if (notOutgoing && AudioGuard.isEnabled(this)) {
            try {
                AudioGuard.enforce(this);
            } catch (Exception ignored) {
            }
        }

        respondAllow(callDetails);
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
