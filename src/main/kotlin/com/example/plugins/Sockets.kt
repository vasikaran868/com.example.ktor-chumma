package com.example.plugins
import com.example.*
import com.example.data.model.User
import com.example.data.model.shuffled_player_list
import com.mongodb.client.model.UpdateOptions
import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.delay
import org.apache.commons.mail.DefaultAuthenticator
import org.apache.commons.mail.SimpleEmail
import org.litote.kmongo.coroutine.coroutine
import org.litote.kmongo.eq
import org.litote.kmongo.reactivestreams.KMongo
import org.litote.kmongo.setTo
import java.text.DateFormat
import java.time.Duration
import java.time.OffsetDateTime
import java.time.OffsetTime
import java.time.ZoneOffset
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.typeOf
import kotlin.time.Duration.Companion.nanoseconds

fun Application.configureSockets() {
    install(WebSockets) {
        pingPeriod = Duration.ofSeconds(15)
        timeout = Duration.ofSeconds(15)
        maxFrameSize = Long.MAX_VALUE
        masking = false
    }

    val a = KMongo.createClient().coroutine
    val b = a.getDatabase("users_database")
    val c= b.getCollection<User>()
    lateinit var game_server:WebSocketSession
    var next_room_number = 1000
    var public_rooms = mutableListOf<Int>()
    val rooms = ConcurrentHashMap<Int,MutableList<String>>()
    val room_admins= ConcurrentHashMap<Int,String>()
    val connected_players = ConcurrentHashMap<String,WebSocketSession>()
    var veri_code_details = mutableMapOf<String,Int>()
    var pass_veri_sessions = mutableMapOf<String,WebSocketSession>()
    val minimum_req = mutableMapOf("BAT" to 4,"BOWL" to 4,"ALL" to 3,"WK" to 2)

    routing {
        webSocket("/") { // websocketSession
            println("new connection")
            for (frame in incoming) {
                when (frame) {
                    is Frame.Text -> {
                        val text = frame.readText()
                        println("IN: ${text}")
                        val params = split_to_list(text)
                        when(params[0].toInt()){
                            //for login
                            0-> {
                                println("login requested : ${params[1]} ${params[2]} ")
                                val user_details = c.findOne(User::username eq params[1])
                                println("found user: $user_details")
                                if(user_details== null){
                                    outgoing.send(Frame.Text("no_user"))
                                    println("OUT: there is no such user")
                                }else{
                                    if (user_details.password == params[2]){
                                        outgoing.send(Frame.Text("logged_in ${params[1]} ${params[2]} ${user_details.email} ${user_details.xp} ${user_details.match_played} ${user_details.match_won} ${user_details.avg_points}"))
                                        connected_players[params[1]] = this
                                        println("$connected_players")
                                        println("OUT: logged in successfully")
                                    }else{
                                        outgoing.send(Frame.Text("password_incorrect"))
                                        println("OUT: password is incorrect")
                                    }
                                }
                            }
                            //for signup
                            1->{
                                println("signup requested: ${params[1]}  ${params[2]}  ${params[3]}  ${params[4]}")
                                val username_check = c.findOne(User::username eq params[1])
                                if (username_check != null){
                                    outgoing.send(Frame.Text("username_exist"))
                                    println("OUT: username already exist")
                                }else{
                                    val id_no= ((c.find().toList().size)+1).toString()
                                    val iuser=User(id = id_no, username = params[1], password = params[2], email = params[3], dob = params[4], xp = 0, match_played = 0, match_won = 0, avg_points = 0F
                                    )
                                    val status=c.insertOne(iuser).wasAcknowledged()
                                    if(status){
                                        outgoing.send(Frame.Text("user_added"))
                                        println("OUT: user added successfully")
                                    }else{
                                        outgoing.send(Frame.Text("failed_add_user"))
                                        println("OUT: failed to add user")
                                    }
                                }
                            }
                            //forget password
                            2->{
                                val user_details = c.findOne(User::username eq params[1])
                                if(user_details== null) {
                                    outgoing.send(Frame.Text("incorrect_username"))
                                }else if(user_details.email != params[2]){
                                    outgoing.send(Frame.Text("email_not_match"))
                                }else if (user_details.email == params[2]){
                                    val veri_code = (1001..9999).random()
                                    veri_code_details[params[1]] = veri_code
                                    pass_veri_sessions[params[1]] = this
                                    game_server.outgoing.send(Frame.Text("send_email: ${params[1]} ${user_details.email} ${veri_code}"))
                                    println("OUT: send_email: ${params[1]} ${user_details.email} ${veri_code}")
                                }
                            }
                            //creating a room
                            3->{
                                println("creating room")
                                println("$next_room_number")
                                rooms.getOrPut(next_room_number,{mutableListOf()}).add(params[1])
                                room_admins[next_room_number]= params[1]
                                println("rooms: ${rooms}")
                                println("admins: ${room_admins} ")
                                outgoing.send(Frame.Text("admin=true"))
                                println("OUT: admin=true")
                                outgoing.send(Frame.Text("createdroom $next_room_number"))
                                println("OUT: createdroom $next_room_number")
                                next_room_number += 1
                            }
                            //joining a room
                            4->{
                                println("join room request ${params[1]} ${params[2]}")
                                val room_no = params[2].toInt()
                                if (rooms.containsKey(room_no)){
                                    println("found room. ${rooms}")
                                    if(rooms[room_no]?.size!! > 3){
                                        outgoing.send(Frame.Text("room_is_filled"))
                                        println("OUT: room_is_filled")
                                    }else{
                                        if (rooms[room_no]!!.size == 0){
                                            outgoing.send(Frame.Text("admin=true"))
                                        }
                                        var pri_or_pub = "private"
                                        if (public_rooms.contains(room_no)){
                                            pri_or_pub = "public"
                                        }
                                        rooms.getOrPut(room_no,{ mutableListOf() }).add(params[1])
                                        var users = "users: $room_no"
                                        rooms[room_no].apply {
                                            for (key in this!!){
                                                users= "${users} ${key}"
                                            }
                                            outgoing.send(Frame.Text("$users"))
                                            println("OUT: $users")
                                            for (key in this!!){
                                                connected_players[key]?.outgoing?.send(Frame.Text("adding_user: ${params[1]}"))
                                                println("OUT: adding_user: ${params[1]}")
                                            }
                                        }
                                        outgoing.send(Frame.Text("joined_room $room_no $pri_or_pub"))
                                        println("OUT: joined_room $room_no")
                                    }
                                }
                                else{
                                    outgoing.send(Frame.Text("Room_does_not_exist"))
                                    println("OUT: Room_does_not_exist")
                                }
                                /*var temp = params[2].toInt()
                                if (temp == 0){

                                    if(public_rooms.size ==0) {
                                        temp = next_room_number
                                        next_room_number += 1
                                        public_rooms.add(temp)
                                    }else if(public_rooms.size != 0){
                                        while(temp == 0){
                                            var i = public_rooms[0]
                                            if( rooms[i]?.keys?.size!!<4){
                                                temp = i
                                            }else{
                                                public_rooms.remove(i)
                                                if(public_rooms.size ==0){
                                                    temp=next_room_number
                                                    next_room_number +=1
                                                    public_rooms.add(temp)
                                                }
                                            }
                                        }
                                    }
                                }
                                if(rooms.containsKey(temp) or (params[2].toInt()==0)){
                                    if(rooms.containsKey(temp) and (params[2].toInt()!=0)){
                                        if(rooms[temp]?.keys?.size!! > 3){
                                            outgoing.send(Frame.Text("room is filled"))
                                        }else{
                                            rooms.getOrPut(temp,{ConcurrentHashMap()})[params[1]] = this
                                            room_admins[temp]=params[1]
                                            var users = "users: $temp"
                                            rooms[temp].apply {
                                                for (key in this?.keys!!){
                                                    users= "${users} ${key}"
                                                }
                                                outgoing.send(Frame.Text("$users"))
                                                for (key in this?.keys!!){
                                                    this[key]?.outgoing?.send(Frame.Text("adding_user: ${params[1]}"))
                                                }
                                            }
                                            if(params[2].toInt()==0){
                                                outgoing.send(Frame.Text("joined_public_room $temp"))
                                            }else if (params[2].toInt()!=0){
                                                outgoing.send(Frame.Text("joined_room $temp"))
                                            }
                                        }
                                    }
                                    else{
                                        rooms.getOrPut(temp,{ConcurrentHashMap()})[params[1]] = this
                                        room_admins[temp]=params[1]
                                        var users = "users: $temp"
                                        rooms[temp].apply {
                                            for (key in this?.keys!!){
                                                users= "${users} ${key}"
                                            }
                                            outgoing.send(Frame.Text("$users"))
                                            for (key in this?.keys!!){
                                                this[key]?.outgoing?.send(Frame.Text("adding_user: ${params[1]}"))
                                            }
                                        }
                                        if(params[2].toInt()==0){
                                            outgoing.send(Frame.Text("joined_public_room $temp"))
                                        }else if (params[2].toInt()!=0){
                                            outgoing.send(Frame.Text("joined_room $temp"))
                                        }
                                    }
                                }else{
                                    outgoing.send(Frame.Text("Room does not exist"))
                                }*/
                            }
                            //change to public room
                            5->{
                                println("to public room request")
                                if (rooms.containsKey(params[1].toInt())){
                                    public_rooms.add(params[1].toInt())
                                    outgoing.send(Frame.Text("changed_to_public_room"))
                                    println("OUT: $public_rooms")
                                    println("OUT: changed_to_public_room")

                                }
                            }
                            //change to private room
                            6->{
                                println("to private room request")
                                if (rooms.containsKey(params[1].toInt()) and public_rooms.contains(params[1].toInt())){
                                    public_rooms.remove(params[1].toInt())
                                    outgoing.send(Frame.Text("changed_to_private_room"))
                                    println("OUT: $public_rooms")
                                    println("OUT: changed_to_private_room")
                                }
                            }
                            //leave a room
                            7->{
                                println("leave a room request")
                                val room_no= params[2].toInt()
                                if (rooms.containsKey(room_no) and (rooms[room_no]?.contains(params[3]) == true)){
                                    if(params[1] != params[3]){
                                        rooms[room_no].apply {
                                            connected_players[params[1]]?.outgoing?.send(Frame.Text("removed_from_room ${params[2]}"))
                                            println("OUT: removed_from_room ${params[2]}")
                                        }
                                    }
                                    else{
                                        outgoing.send(Frame.Text("left_room ${params[2]}"))
                                        println("OUT: left_room ${params[2]}")
                                    }
                                    rooms.getOrPut(room_no,{ mutableListOf() }).remove(params[1])
                                    println(rooms)

                                    rooms[room_no].apply {
                                        for (key in this!!){
                                            connected_players[key]?.outgoing?.send(Frame.Text("deleting_user: ${params[1]}"))
                                            println("OUT: deleting_user: ${params[1]}")
                                        }
                                    }
                                }
                                if (params[1]==room_admins[room_no]){
                                    if (rooms[room_no]?.size ==0){
                                        rooms.remove(room_no)
                                    }else{
                                        rooms[room_no].apply {
                                            val admin = this?.random()
                                            println("$admin")
                                            connected_players[admin]?.outgoing?.send(Frame.Text("admin=true"))
                                            println("OUT: admin=true")
                                        }
                                    }
                                }
                                if (rooms[room_no]?.size ==0){
                                    rooms.remove(room_no)
                                }
                            }
                            //start the game
                            8->{
                                println("start the game request")
                                val room_no = params[1].toInt()
                                val room_members= rooms[room_no]
                                val no_of_memeber = room_members!!.size
                                val no_of_batsmen = no_of_memeber * minimum_req["BAT"]!!
                                val no_of_indian_batsmen = ((Math.ceil ((no_of_batsmen.toDouble()) / 3)) * 2) +2
                                val no_of_foreign_batsmen = (Math.ceil ((no_of_batsmen.toDouble()) / 3)) + 2
                                val no_of_bowler = no_of_memeber * minimum_req["BOWL"]!!
                                val no_of_indian_bowler = ((Math.ceil ((no_of_bowler.toDouble()) / 3)) * 2) +2
                                val no_of_foreign_bowler = (Math.ceil ((no_of_bowler.toDouble()) / 3)) + 2
                                val no_of_allrounder = no_of_memeber * minimum_req["ALL"]!!
                                val no_of_indian_allrounder = ((Math.ceil ((no_of_allrounder.toDouble()) / 3)) * 2) +2
                                val no_of_foreign_allrounder = (Math.ceil ((no_of_allrounder.toDouble()) / 3)) + 2
                                val no_of_wk = no_of_memeber * minimum_req["WK"]!!
                                val no_of_indian_wk = ((Math.ceil ((no_of_wk.toDouble()) / 3)) * 2) +2
                                val no_of_foreign_wk = (Math.ceil ((no_of_wk.toDouble()) / 3)) + 2
                                val players_list = mutableListOf<String>()
                                val ind_batsmen_list = indian_batsmen_list.shuffled().subList(0,no_of_indian_batsmen.toInt())
                                val for_batsmen_list = foreign_batsmen_list.shuffled().subList(0,no_of_foreign_batsmen.toInt())
                                val ind_bowler_list = indian_bowlers_list.shuffled().subList(0,no_of_indian_bowler.toInt())
                                val for_bowler_list = foreign_bowlers_list.shuffled().subList(0,no_of_foreign_bowler.toInt())
                                val ind_allrounders_list = indian_allrounders_list.shuffled().subList(0,no_of_indian_allrounder.toInt())
                                val for_allrounders_list = foreign_allrounders_list.shuffled().subList(0,no_of_foreign_allrounder.toInt())
                                val ind_wk_list = indian_wk_list.shuffled().subList(0,no_of_indian_wk.toInt())
                                val for_wk_list = foreign_wk_list.shuffled().subList(0,no_of_foreign_wk.toInt())
                                players_list.addAll(ind_batsmen_list)
                                players_list.addAll(for_batsmen_list)
                                players_list.addAll(ind_bowler_list)
                                players_list.addAll(for_bowler_list)
                                players_list.addAll(ind_allrounders_list)
                                players_list.addAll(for_allrounders_list)
                                players_list.addAll(ind_wk_list)
                                players_list.addAll(for_wk_list)
                                val room_players_list = players_list.shuffled()
                                //val room_players_list = temp_players_list.shuffled()
                                var players_image_uri_string = ""
                                for (player in room_players_list){
                                    val player_params = split_to_list(player)
                                    players_image_uri_string = players_image_uri_string + "${player_params[0]}_${player_params[1]} ${players_image_uri["${player_params[0]}_${player_params[1]}"]} "
                                }
                                val start_time = OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(15)
                                println("game details: $room_players_list $room_no $start_time $room_members")
                                rooms[room_no].apply {
                                    for (key in this!!) {
                                        connected_players[key]?.outgoing?.send(Frame.Text("players: $room_players_list"))
                                        connected_players[key]?.outgoing?.send(Frame.Text("players_image_list: $players_image_uri_string"))
                                        connected_players[key]?.outgoing?.send(Frame.Text("game_start: 80 ${start_time} ${room_members}"
                                        ))
                                    }
                                }
                                val bid_end_time = start_time.plusSeconds(8)
                                println("to game server: room_details: ${room_no} ${start_time} ${bid_end_time} ${room_members} ${room_players_list}")
                                println("to game server: room_details: ${players_image_uri_string}")
                                game_server.outgoing.send(Frame.Text("room_details: ${room_no} ${start_time} ${bid_end_time} ${room_members} ${room_players_list}"))
                                println("to game server: room_details: ${room_no} ${start_time} ${bid_end_time} ${room_members} ${room_players_list}")
                                println("to game server: room_details: ${players_image_uri_string}")
                            }
                            10->{//connects to game server
                                println("game server contacting")
                                game_server= this
                                outgoing.send(Frame.Text("hello server"))
                                println("OUT: hello server")
                            }
                            //sending server time
                            11->{
                                println("time request")
                                outgoing.send(Frame.Text("time ${OffsetDateTime.now(ZoneOffset.UTC)}"))
                                println("OUT: time ${OffsetDateTime.now(ZoneOffset.UTC)}")
                            }
                            12->{//sent game details to game server...connect to game server
                                println("sent game details to game server...connect to game server")
                                val room_no = params[1].toInt()
                                rooms[room_no].apply {
                                    for (key in this!!) {
                                        connected_players[key]?.outgoing?.send(Frame.Text("connect_to_game_server"))
                                    }
                                }
                            }
                            13->{//verification code sent confirmation
                                println("verification code sent confirmation")
                                println(pass_veri_sessions)
                                pass_veri_sessions[params[1]]!!.outgoing.send(Frame.Text("veri_code_sent"))
                                println("OUT: veri_code_sent")
                            }
                            14->{//otp checking
                                println("otp checking")
                                val veri_code = veri_code_details[params[1]]
                                if(veri_code == params[2].toInt()){
                                    this.outgoing.send(Frame.Text("otp_verified"))
                                    println("OUT: otp_verified")
                                }
                            }
                            15->{//change password
                                println("password change request")
                                val new_pass = params[1]
                                val username = params[2]
                                val user_details = c.findOne(User::username eq username)
                                println(user_details)
                                val updated_user = user_details!!.copy(password = new_pass)
                                println(updated_user)
                                c.updateOne(User::username eq username, updated_user, UpdateOptions())
                                this.outgoing.send(Frame.Text("changed_password ${new_pass}"))
                            }

                            16->{
                                println("OUT: username change request")
                                val current_username = params[2]
                                val new_username = params[1]
                                val user_details = c.findOne(User::username eq current_username)
                                println(user_details)
                                val updated_user = user_details!!.copy(username = new_username)
                                println(updated_user)
                                c.updateOne(User::username eq current_username, updated_user, UpdateOptions())
                                connected_players[new_username]=this
                                this.outgoing.send(Frame.Text("changed_username ${new_username}"))
                            }

                            17->{
                                println("email change request")
                                val username = params[2]
                                val new_email = params[1]
                                val user_details = c.findOne(User::username eq username)
                                println(user_details)
                                val updated_user = user_details!!.copy(email = new_email)
                                println(updated_user)
                                c.updateOne(User::username eq username, updated_user, UpdateOptions())
                                this.outgoing.send(Frame.Text("changed_email ${new_email}"))
                            }

                            18->{//all public rooms details
                                println("all public rooms details")
                                var msg = "pub_room"
                                for (room in public_rooms){
                                    msg = msg+ " $room:${rooms.getOrPut(room,{ mutableListOf() }).toList()}"
                                }
                                outgoing.send(Frame.Text("$msg"))
                                println("OUT: $msg")
                            }
                            //user stats upadte request
                            19->{
                                println("user game stats update")
                                val user_details = c.findOne(User::username eq params[1])
                                println(user_details)
                                val updated_user = user_details!!.copy(xp=params[2].toInt(), match_played = params[3].toInt(), match_won = params[4].toInt(), avg_points = params[5].toFloat())
                                println(updated_user)
                                c.updateOne(User::username eq params[1], updated_user, UpdateOptions())
                            }
                            20->{
                                println("websocket reconnection request")
                                connected_players[params[1]] = this
                            }

                        }
                        if (text.equals("bye", ignoreCase = true)) {
                            close(CloseReason(CloseReason.Codes.NORMAL, "Client said BYE"))
                        }
                    }
                }
            }
        }
    }
}


fun split_to_list(text: String): List<String> = text.trim().split("\\s+".toRegex())


