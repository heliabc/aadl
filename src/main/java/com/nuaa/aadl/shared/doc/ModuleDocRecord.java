package com.nuaa.aadl.shared.doc;
// 作用是 定义一个不可变的记录类 `ModuleDocRecord`，
// 用于表示模块文档的记录信息，包括文档的 JSON 内容、版本号和更新时间。这个类主要用于在模块文档存储和检索过程中传递文档数据。
public record ModuleDocRecord(
    String payloadJson,
    int version,
    String updatedAt
) {
}
