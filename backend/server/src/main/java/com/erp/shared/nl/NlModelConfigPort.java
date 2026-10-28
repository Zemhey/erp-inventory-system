package com.erp.shared.nl;

/**
 * 自然语言模型配置的读写端口（FR-SYS-04）。
 *
 * <p><b>为什么是"端口"而不是直接注入 Service</b>：
 * {@code com.erp.nl} 一侧（解析器、复核器、设置接口）需要读配置，但它不能碰仓储
 * （架构规则：nl 不得依赖 {@code ..repository..}）；真正的存储实现又必须待在
 * {@code com.erp.sys}（只有它能碰仓储，且 sys 只能依赖 shared）。
 * 两边唯一的公共落点就是 {@code com.erp.shared.nl}：接口定义在这里，
 * 实现在 sys，nl 只依赖接口。运行时由 Spring 把实现注进来。
 *
 * <p><b>实现方必须做到"保存即生效"</b>：{@link #save} 之后，
 * 下一次 {@link #current} 就要返回新值，不允许要求重启。
 * 语义上等价于"配置是随时会变的，调用方不要把它缓存在字段里"。
 */
public interface NlModelConfigPort {

    /** 当前生效的配置。永不返回 null；DB 缺行时返回 {@link NlModelSettings#defaults()}。 */
    NlModelSettings current();

    /** 整体覆盖保存。实现方负责加密 {@code apiKey}，并让缓存立即失效。 */
    void save(NlModelSettings settings);

    /**
     * 配置是否已被显式配置过（用于界面区分"默认值"与"用户填过"）。
     * 实现方可按"DB 里有没有 api-key 行"判断，缺省实现返回 {@code !apiKey.isBlank()}。
     */
    default boolean configured() {
        return !current().apiKey().isBlank();
    }
}
