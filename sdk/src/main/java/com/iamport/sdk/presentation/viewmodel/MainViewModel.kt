package com.iamport.sdk.presentation.viewmodel

import androidx.lifecycle.LiveData
import androidx.lifecycle.viewModelScope
import com.iamport.sdk.data.sdk.IamPortApprove
import com.iamport.sdk.data.sdk.IamPortResponse
import com.iamport.sdk.data.sdk.Payment
import com.iamport.sdk.domain.repository.StrategyRepository
import com.iamport.sdk.domain.strategy.base.JudgeStrategy
import com.iamport.sdk.domain.utils.Event
import com.iamport.sdk.domain.utils.NativeLiveDataEventBus
import com.orhanobut.logger.Logger
import com.orhanobut.logger.Logger.d
import com.orhanobut.logger.Logger.i
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.koin.core.component.KoinApiExtension
import org.koin.core.component.KoinComponent

@KoinApiExtension
class MainViewModel(private val bus: NativeLiveDataEventBus, private val repository: StrategyRepository) : BaseViewModel(), KoinComponent {

    private var job = Job()
        get() {
            if (field.isCancelled) field = Job()
            return field
        }

    override fun onCleared() {
        d("onCleared")
        clearData()
        super.onCleared()
    }


    /**
     * 결제 데이터
     */
    fun webViewPayment(): LiveData<Event<Payment>> {
        return bus.webViewPayment
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
    fun judgePayment(payment: Payment) {
        viewModelScope.launch(job) {
            repository.judgeStrategy.judge(payment).run {
                d("$this")
                when (first) {
                    JudgeStrategy.JudgeKinds.CHAI -> second?.let { repository.chaiStrategy.doWork(it.pg_id, third) }
                    JudgeStrategy.JudgeKinds.WEB -> bus.webViewPayment.postValue(Event(third))
                    else -> Logger.e("판단불가 $third")
                }
            }
        }
    }


    /**
     * 차이 데이터 클리어
     */
    fun clearData() {
        repository.chaiStrategy.init()
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
     * 차이 결제 스테이터스 확인 with 폴링
     */
    fun pollingChaiStatus() {
        viewModelScope.launch(job) {
            d("백그라운드라서 폴링 시도")
            repository.chaiStrategy.requestPollingChaiStatus()
        }
    }


    /**
     * 차이 결제 스테이터스 확인
     */
    fun checkChaiStatus() {
        viewModelScope.launch(job) {
            d("차이앱 종료돼서 차이 결제 상태 체크")
            repository.chaiStrategy.requestCheckChaiStatus()
        }
    }

}
