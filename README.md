

# 1. 项目配置方式
## 1.1 Java
1. 安装JDK>17开发环境：[安装教程](https://blog.csdn.net/tiehou/article/details/129575138)
2. 安装IDEA开发工具，可以试用一段时间
	- [安装教程](https://blog.csdn.net/weixin_49070591/article/details/123723855)
	- [运行项目配置教程](https://bs.javatip.cn/runguide/idea-springboot-run-guide/)
3. 安装插件：LomBok必须安装
4. 可能问题
	- 运行遇到get方法确实问题：1）安装插件LomBok；2）配置Annotation processing为自动获取。

## 1.2 C++（开发者补充）

## 1.3 Python（开发者补充）


# 2. 项目参考架构

```python
trading-simulator/
├── trading-service/（端口 8081）           # 现有 Spring Boot 项目
├── native-modules/
│   ├── cpp/（端口 9001）
│   │   └── matcher/        # C++ 高性能撮合
│   └── python/（端口 9002）
│       └── risk/           # Python 风控/原型
├── protocol/               # IPC协议/功能字段定义（JSON 示例、proto 草案）
└── docs/                   # 任务书，周报模板
```


# 3、需求管理

> ——> 计划仅仅使用分支管理，简化开发流程。
> 
> **见3.1**

## 3.1 Git约定分支规范
main：主分支（仅合并已验收的 PR）；

develop：开发分支（日常合并功能 PR）；

`feature/<module>-<name>`：功能分支；
- 必须说明：如何启动你的服务；使用的端口 / 接口

bugfix/ID-问题：bug 修复分支。

> 使用示例：
> 不要在main进行开发，每个同学基于develop分支新建（如：`feature/01-order-develop`）
> 
> 创建分支：`git checkout -b feature/01-order-develop`
>
>代码提交远程个人开发分支：`git push origin feature/01-order-develop`

>管理员操作：（在develop分支下）
>```
>	拉dev分支：git pull
>	-> 切换分支：git checkout feature/01-order-develop
>	-> 拉feature分支：git pull
>	-> 切换分支：git checkout develop
>	-> 合并代码：git merge feature/01-order-develop
>```

## 3.2 Issues 管理需求全生命周期
1. 第一步：标准化需求提报

- 在项目根目录创建 .github/ISSUE_TEMPLATE/feature_request.md 文件；
- 模板包含核心字段：需求背景、用户故事、验收标准、优先级、关联模块（Java/C++）、预估工作量。

2. 第二步：用 Labels（标签）分类管理需求

    | 标签类型 | 示例标签 | 作用 |
    |----------|----------|------|
    | 需求类型 | type/feature（新功能）、type/bug（bug 修复）、type/optimize（优化） | 区分需求性质 |
    | 状态 | status/to-do（待办）、status/in-progress（开发中）、status/review（待验收）、status/done（完成） | 跟踪需求进度 |
    | 所属模块 | module/java、module/cpp、module/ipc | 关联到 Java/C++ 模块，方便分工 |
    | 优先级 | priority/high、priority/medium、priority/low | 区分需求紧急程度 |

3. 第三步：用 Milestone（里程碑）规划需求版本

    把相关需求归类到里程碑（比如 “v1.0 基础 IPC 通信”），明确版本交付目标：
    - 比如里程碑 “v1.0” 包含：Java 侧 IPC 接口开发、C++ 侧 IPC 客户端开发、数据库访问接口开发；
    - 每个里程碑可设置截止日期，跟踪版本进度。


4. 第四步：用 Projects（看板）可视化需求流程

    GitHub Projects 是可视化看板，把 Issue 拖到不同列，直观看到需求状态：

        1) 新建 Project，添加列：待办（To Do） → 开发中（In Progress） → 待验收（Review） → 已完成（Done）；

        2) 把 Issue 拖到对应列（比如你负责的 Java IPC 接口 Issue 拖到 “开发中”，C++ 侧的拖到 “待办”）；

        3) 可按模块、优先级筛选，方便团队同步进度。

5. 第五步：需求拆解（大需求拆小 Issue）

    如果需求较大（比如 “完成 Java 和 C++ 的 IPC 通信”），拆成多个小 Issue，便于分工和跟踪：

    - 主 Issue：【需求】完成 Java 和 C++ 的 IPC 通信（标记epic标签）；
    子 Issue：

        - 【需求】Java 侧开发 IPC Socket 服务端（分配给你）；

        - 【需求】C++ 侧开发 IPC Socket 客户端（分配给 C++ 开发者）；

        - 【需求】定义 IPC 通信的 Protobuf 消息格式（双方协作）；

    - 子 Issue 通过Closes #主IssueID关联，主 Issue 会自动跟踪子 Issue 进度。

## 3.3 用 PR 关联 Issue，完成需求落地
> PR 是代码提交和合并的载体，核心作用是 “把代码开发和需求关联起来”，确保每一行代码都对应具体需求，避免无目的的代码提交。

[PR和Issue联动教程](https://howtosos.eryajf.net/01-basic-content/04-PR-and-issue.html#pr%E4%B8%8Eissue%E7%9A%84%E8%81%94%E5%8A%A8%E7%BB%B4%E6%8A%A4)

[PR创建教程](https://blog.csdn.net/qq_39668099/article/details/153315660)


## 3.4 完整流程示例
1. 提需求：创建 Issue #10：【需求】Java 侧开发 IPC Socket 服务端（标签：type/feature、module/java、priority/high）；
2. 建分支：你从develop分支创建feature/10-java-ipc-server；
3. 写代码：完成 Java IPC 服务端开发，提交代码到该分支；
4. 提 PR：创建 PR，描述中写Closes #10，指定 C++ 开发者为审核人；
5. 审核合并：C++ 开发者审核代码通过，合并 PR 到develop；
6. 需求闭环：PR 合并后，Issue #10 自动关闭，Projects 看板中该 Issue 拖到 “已完成” 列；
7. 后续联动：C++ 开发者基于 #10 完成的服务端，开发 #11 号 Issue 的客户端，重复上述流程。

