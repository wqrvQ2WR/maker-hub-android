package com.hayoonjae.makerhub

import androidx.annotation.DrawableRes

/**
 * 하단 탭 하나 = 사이트 하나. 탭마다 웹뷰가 따로 살아 있어서 오가도 페이지가 유지된다.
 * [home] 이 비어 있으면 주소를 앱 안에서 직접 넣고 저장하는 탭이다(lmphone 처럼 주소가 바뀌는 경우).
 */
enum class Site(
    val label: String,
    val home: String,
    val colorStart: Int,
    val colorEnd: Int,
    @param:DrawableRes val icon: Int,
) {
    RASPBERRY_PI(
        "Pi", "https://www.raspberrypi.com/",
        0xFFD6155B.toInt(), 0xFFFF5E9C.toInt(), R.drawable.ic_tab_raspberrypi,
    ),
    ARDUINO(
        "Arduino", "https://www.arduino.cc/",
        0xFF00979D.toInt(), 0xFF1DD8C4.toInt(), R.drawable.ic_tab_arduino,
    ),
    CONNECT(
        "Connect", "https://connect.raspberrypi.com/sign-in",
        0xFF7B2FF7.toInt(), 0xFFF107A3.toInt(), R.drawable.ic_tab_connect,
    ),
    WOKWI(
        "Wokwi", "https://wokwi.com/",
        0xFF2563FF.toInt(), 0xFF00C6FF.toInt(), R.drawable.ic_tab_wokwi,
    ),
    HMD(
        "HMD", "https://www.hmd.com/en_int/all-phones",
        0xFFFF5F00.toInt(), 0xFFFFB300.toInt(), R.drawable.ic_tab_hmd,
    ),

    /** 맥에서 도는 lmphone(LM Studio 채팅). 터널 주소가 켤 때마다 바뀌어서 주소를 앱에 저장해 쓴다. */
    LMPHONE(
        "lmphone", "",
        0xFF0EA85C.toInt(), 0xFF7BE495.toInt(), R.drawable.ic_tab_lmphone,
    ),

    ;

    val needsOwnAddress: Boolean get() = home.isEmpty()
}
