package org.agentos.sample.todo.data

/** 存储抽象：仓库只通过它读写，JVM 测试用内存实现，App 里用 [SqliteTodoStore]。方法是阻塞的，由仓库放到 IO 线程调用。 */
interface TodoStore {
    fun loadAll(): List<Todo>

    /** 插入或整条覆盖。 */
    fun save(todo: Todo)

    /** 同一个事务里插入或整条覆盖（撤销删除时一次写回父任务和它的子任务）。 */
    fun saveAll(todos: Collection<Todo>)

    fun delete(ids: Collection<String>)

    /** 库里实际的行数（debug 的 reset 用它核对“真的清空了”，不看内存缓存）。 */
    fun count(): Int
}
