---
name: 功能需求
about: 提报项目新功能需求（Java/C++ IPC项目专用）
title: "[需求] "
labels: type/feature, status/to-do
assignees: ""
---

## 需求背景
（比如：C++模块需要通过IPC从Java获取用户数据库信息）

## 用户故事
（比如：作为C++开发者，我希望调用Java的IPC接口，传入用户ID就能获取用户信息）

## 验收标准
1. 接口入参：用户ID（Long类型），非空
2. 接口出参：JSON格式的用户信息（包含id/name/phone）
3. 响应超时时间≤500ms

## 关联模块
- [ ] Java模块（IPC通信接口开发）
- [ ] C++模块（IPC客户端调用）

## 优先级
- [ ] 高（必须）
- [ ] 中（重要）
- [ ] 低（可选）

## 预估工作量
（比如：Java侧2小时，C++侧1小时）