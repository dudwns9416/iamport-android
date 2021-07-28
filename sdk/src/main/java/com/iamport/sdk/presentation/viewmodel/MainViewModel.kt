package com.iamport.sdk.presentation.viewmodel

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.viewModelScope
import com.iamport.sdk.data.sdk.IamPortApprove
import com.iamport.sdk.data.sdk.IamPortResponse
import com.iamport.sdk.data.sdk.Payment
import com.iamport.sdk.domain.core.IamportReceiver
import com.iamport.sdk.domain.di.IamportKoinComponent
import com.iamport.sdk.domain.repository.StrategyRepository
import com.iamport.sdk.domain.service.ChaiService
import com.iamport.sdk.domain.strategy.base.JudgeStrategy
import com.iamport.sdk.domain.utils.CONST
import com.iamport.sdk.domain.utils.Event
import com.iamport.sdk.domain.utils.NativeLiveDataEventBus
import com.orhanobut.logger.Logger
import com.orhanobut.logger.Logger.d
import com.orhanobut.logger.Logger.i
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.koin.core.component.KoinApiExtension

@KoinApiExtension
class MainViewModel(private val bus: NativeLiveDataEventBus, private val repository: StrategyRepository, application: Application) :
    AndroidViewModel(application), IamportKoinComponent {

    // AndroidViewModel 이기에 사용가능
    val app = getApplication<Application>()

    var payment: Payment? = null

    private val screenBrFilter = IntentFilter(Intent.ACTION_SCREEN_OFF).apply {
        addAction(Intent.ACTION_SCREEN_ON)
    }

    private var job = Job()
        get() {
            if (field.isCancelled) field = Job()
            return field
        }

    var playChai: Boolean
        get() {
            return bus.playChai
        }
        set(value) {
            bus.playChai = value
        }

    var chaiClearVersion: Boolean
        get() {
            return bus.chaiClearVersion
        }
        set(value) {
            bus.chaiClearVersion = value
        }

    var receiveChaiCallBack: Boolean
        get() {
            return bus.receiveChaiCallBack
        }
        set(value) {
            bus.receiveChaiCallBack = value
        }


    override fun onCleared() {
        d("onCleared")
        clearData()
        super.onCleared()
    }

    fun failSdkFinish() {
        payment?.let {
            repository.failSdkFinish(it)
        } ?: run {
            Logger.w("Payment 데이터가 없어서 실패처리하지 않음 [$payment]")
        }
    }

    /**
     * 결제 데이터
     */
    fun webViewActivityPayment(): LiveData<Event<Payment>> {
        return bus.webViewActivityPayment
    }

    /**
     * 차이앱 열기
     */
    fun chaiUri(): LiveData<Event<String>> {
        return bus.chaiUri
    }


    /**
     * 차이 결제 상태 approve
     */
    fun chaiApprove(): LiveData<Event<IamPortApprove>> {
        return bus.chaiApprove
    }


    /**
     * 결제 결과 콜백 및 종료
     */
    fun impResponse(): LiveData<Event<IamPortResponse?>> {
        return bus.impResponse
    }


    /**
     * 외부 노출용 폴링여부
     */
    fun isPolling(): LiveData<Event<Boolean>> {
        return bus.isPolling
    }


    /**
     * 결제 요청
     */
    fun judgePayment(payment: Payment, ignoreNative: Boolean = false) {
        viewModelScope.launch(job) {
            repository.judgeStrategy.judge(payment, ignoreNative = ignoreNative).run {

                Payment.validator(third).run {
                    if (!first) {
                        bus.impResponse.postValue(Event(second?.let { IamPortResponse.makeFail(payment, msg = it) }))
                        return@launch
                    }
                }

                d("$this")
                when (first) {
                    JudgeStrategy.JudgeKinds.CHAI -> second?.let { repository.chaiStrategy.doWork(it.pg_id, third) }
                    JudgeStrategy.JudgeKinds.WEB,
                    JudgeStrategy.JudgeKinds.CERT -> bus.webViewActivityPayment.postValue(Event(third))
                    else -> Logger.e("판단불가 $third")
                }
            }
        }
    }


    /**
     * 차이 데이터 클리어
     */
    fun clearData() {

        // 차이 관련 초기화
        playChai = false
        chaiClearVersion = false
        receiveChaiCallBack = false

        // repository 초기화
        repository.init()

        // 코루틴 job cancel
        job.cancel()
    }


    /**
     * 차이 최종 결제 요청
     */
    fun requestApprovePayments(approve: IamPortApprove) {
        viewModelScope.launch(job) {
            i("차이 최종 결제 요청")
            repository.chaiStrategy.requestApprovePayments(approve)
        }
    }

    /**
     * ON_STOP시 차이 결제 스테이터스 확인 with 폴링
     */
    fun pollingChaiStatus() {
        if (!playChai) {
            d("ignore pollingChaiStatus cause playChai is false")
            return
        }

        viewModelScope.launch(job) {
            d("백그라운드라서 폴링 시도")
            repository.chaiStrategy.requestPollingChaiStatus()
        }
    }


    /**
     * ON_START시 차이 결제 스테이터스 확인
     */
    fun checkChaiStatus() {
        if (!playChai) {
            d("ignore checkChaiStatus cause playChai is false")
            return
        }

        if (receiveChaiCallBack) {
            d("ignore checkChaiStatus cause receiveChaiCallBack")
            receiveChaiCallBack = false // 스킴 액티비티 종료시에 불릴 수 있기 때문에 초기화 해줘야함
            return
        }

        viewModelScope.launch(job) {
            d("차이앱 종료돼서 차이 결제 상태 체크")
            repository.chaiStrategy.requestCheckChaiStatus()
        }
    }

    /**
     * ResultCallback시 차이 결제 스테이터스 확인
     */
    fun checkChaiStatusForResultCallback() {
        checkChaiStatus()
        receiveChaiCallBack = true
    }


    fun registerIamportReceiver(iamportReceiver: IamportReceiver, screenBrReceiver: BroadcastReceiver) {
        IntentFilter().let {
            it.addAction(CONST.BROADCAST_FOREGROUND_SERVICE)
            it.addAction(CONST.BROADCAST_FOREGROUND_SERVICE_STOP)
            app.applicationContext?.registerReceiver(screenBrReceiver, screenBrFilter)
            app.registerReceiver(iamportReceiver, it)
        }
    }


    // 포그라운드 서비스 관련 BroadcastReceiver,
    // 스크린 ON/OFF 브로드캐스트 리시버 초기화
    fun unregisterIamportReceiver(iamportReceiver: IamportReceiver, screenBrReceiver: BroadcastReceiver) {
        runCatching {
            app.unregisterReceiver(iamportReceiver)
            app.applicationContext?.unregisterReceiver(screenBrReceiver)
        }
    }


    fun controlForegroundService(it: Boolean) {
        if (!ChaiService.enableForegroundService) {
            d("차이 폴링 포그라운드 서비스 실행하지 않음")
            return
        }

        app.run {
            Intent(this, ChaiService::class.java).also { intent: Intent ->
                if (it) {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        startForegroundService(intent)
                    } else {
                        startService(intent)
                    }
                } else {
                    stopService(intent)
                }
            }
        }
    }
}
