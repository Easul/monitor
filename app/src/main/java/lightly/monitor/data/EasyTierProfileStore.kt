package lightly.monitor.data
import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import org.json.JSONArray
private val Context.easyTierDataStore by preferencesDataStore("easytier_profiles")
class EasyTierProfileStore(private val context:Context){private val profilesKey= stringPreferencesKey("easytier_profiles");private val selectedKey= stringPreferencesKey("easytier_selected_profile_id");private val autoStartControlledKey= booleanPreferencesKey("auto_start_controlled")
 suspend fun loadProfiles():List<EasyTierNetworkProfile>{val raw=context.easyTierDataStore.data.first()[profilesKey];val profiles=decode(raw);if(profiles.isNotEmpty())return profiles;val d=createDefaultProfile();saveProfiles(listOf(d));setSelectedProfileId(d.id);return listOf(d)}
 suspend fun saveProfiles(profiles:List<EasyTierNetworkProfile>){context.easyTierDataStore.edit{it[profilesKey]=JSONArray(profiles.map{p->p.toJson()}).toString();val s=it[selectedKey];if(profiles.isNotEmpty()&&s!=null&&profiles.none{p->p.id==s})it[selectedKey]=profiles.first().id}}
  suspend fun getSelectedProfileId():String?=context.easyTierDataStore.data.first()[selectedKey]
  suspend fun setSelectedProfileId(id:String){context.easyTierDataStore.edit{it[selectedKey]=id}}
  suspend fun isAutoStartControlledEnabled():Boolean=context.easyTierDataStore.data.first()[autoStartControlledKey]?:false
  suspend fun setAutoStartControlledEnabled(enabled:Boolean){context.easyTierDataStore.edit{it[autoStartControlledKey]=enabled}}
 fun createProfile(name:String?=null,config:EasyTierConfig=EasyTierConfig.default()):EasyTierNetworkProfile{val now=System.currentTimeMillis();return EasyTierNetworkProfile((now*1000).toString(),name?:config.networkName,config,now,now)}
 fun createDefaultProfile()=createProfile("Default Network",EasyTierConfig.default())
 private fun decode(raw:String?):List<EasyTierNetworkProfile>{if(raw.isNullOrBlank())return emptyList();return runCatching{val a=JSONArray(raw);(0 until a.length()).mapNotNull{a.optJSONObject(it)?.let(EasyTierNetworkProfile::fromJson)}}.getOrDefault(emptyList())}}
