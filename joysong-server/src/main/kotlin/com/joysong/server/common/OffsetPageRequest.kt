package com.joysong.server.common
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
data class OffsetPageRequest(private val start: Long, private val size: Int) : Pageable {
 init { require(start >= 0); require(size > 0) }
 override fun getOffset()=start; override fun getPageSize()=size; override fun getPageNumber()=(start/size).toInt()
 override fun getSort():Sort=Sort.unsorted(); override fun next():Pageable=copy(start=start+size)
 override fun previousOrFirst():Pageable=if(hasPrevious())copy(start=start-size)else first(); override fun first():Pageable=copy(start=0)
 override fun withPage(pageNumber:Int):Pageable=copy(start=pageNumber.toLong()*size); override fun hasPrevious()=start>=size
}
