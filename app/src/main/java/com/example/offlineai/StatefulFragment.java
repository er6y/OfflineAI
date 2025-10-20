package com.example.offlineai;

import android.os.Bundle;

/**
 * Fragment状态保存和恢复接口
 * 
 * 所有需要保存状态的Fragment都应该实现这个接口
 * MainActivity会统一调用这些方法来管理Fragment状态
 * 
 * 使用场景：
 * 1. app切后台后再切回来，状态能够恢复
 * 2. 熄屏后再点亮屏幕，状态依然保留
 * 3. 系统内存不足杀掉app后重启，状态能够恢复
 */
public interface StatefulFragment {
    
    /**
     * 保存Fragment的状态
     * 
     * @return 包含所有需要保存的状态数据的Bundle
     *         返回null表示没有状态需要保存
     * 
     * 建议保存的内容：
     * - 用户输入的文本
     * - 选择的下拉菜单项（索引或值）
     * - 聊天历史记录
     * - 选中的文件/图片路径
     * - 任务进度状态
     * - 其他UI状态
     */
    Bundle saveState();
    
    /**
     * 恢复Fragment的状态
     * 
     * @param state 之前通过saveState()保存的状态Bundle
     *              如果为null，表示没有保存的状态
     * 
     * 注意：
     * 1. 应该在Fragment的View创建之后调用（onViewCreated之后）
     * 2. 需要做好空值检查，因为state可能为null
     * 3. 需要检查Bundle中的key是否存在
     */
    void restoreState(Bundle state);
    
    /**
     * 获取Fragment的唯一标识符
     * 用于在保存和恢复状态时区分不同的Fragment
     * 
     * @return Fragment的唯一标识符，通常使用类名
     */
    default String getFragmentId() {
        return this.getClass().getSimpleName();
    }
}
