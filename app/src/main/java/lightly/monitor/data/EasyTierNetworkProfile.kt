package lightly.monitor.data
import org.json.JSONObject

data class EasyTierNetworkProfile(val id:String,val name:String,val config:EasyTierConfig,val createdAt:Long,val updatedAt:Long){
 fun toJson()=JSONObject().put("id",id).put("name",name).put("config",config.toJson()).put("createdAt",createdAt).put("updatedAt",updatedAt)
 companion object{fun fromJson(j:JSONObject)=EasyTierNetworkProfile(j.optString("id"),j.optString("name","Default Network"),EasyTierConfig.fromJson(j.optJSONObject("config")?:JSONObject()),j.optLong("createdAt",System.currentTimeMillis()),j.optLong("updatedAt",System.currentTimeMillis()))}
}
