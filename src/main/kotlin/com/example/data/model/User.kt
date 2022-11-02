package com.example.data.model

import org.bson.codecs.pojo.annotations.BsonId

data class User(
     @BsonId
     val id: String,
     val username:String,
     var password:String,
     val email:String,
     val dob:String,
     var xp:Int,
     val match_played:Int,
     val match_won:Int,
     val avg_points:Float
)
