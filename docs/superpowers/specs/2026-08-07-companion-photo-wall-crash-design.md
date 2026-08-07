# Daddy 小屋照片墙崩溃修复设计

## 目标

让已经保存照片的小屋稳定打开、横向浏览照片，并保留原有的题字与取下操作。

## 根因

照片墙把 `LazyRow` 放在 Material3 `ListItem` 的 `supportingContent` 内。照片出现后，`ListItem` 会对内容请求固有尺寸；`LazyRow` 基于 `SubcomposeLayout`，不支持该测量方式，因此主线程抛出 `IllegalStateException`。照片记录已写入本地设置，所以每次再次打开小屋都会重复触发。

## 方案

将照片墙从 `CardGroup` / `ListItem` 中移出，改为独立的 `PhotoWallSection`：普通 `Surface` + `Column` 承载文案和按钮，照片横条仍使用固定高度的 `LazyRow`。该组件不使用 `IntrinsicSize`，也不把懒加载布局嵌进会请求固有尺寸的 Material3 `ListItem`。

照片的 `uri`、题字、删除逻辑和备份数据结构保持不变；已有照片无需迁移或清除。Coil 图片加载失败时维持其安全错误状态，不使整个页面失效。

## 验证

新增 Android Compose 回归测试：以已保存的一张照片组合 `PhotoWallSection`，确认照片题字和“取下”按钮可见且按钮回调正常。该测试在旧代码中因组件不存在而无法编译；实现后可编译，并可在 Android 设备上运行。另构建 companion APK，由用户在真实手机上验证“已有照片后重新进入小屋”。
