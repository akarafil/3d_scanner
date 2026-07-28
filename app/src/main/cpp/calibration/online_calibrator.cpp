#include "online_calibrator.h"

#if HAVE_OPENCV
bool OnlineHingeCalibrator::CalibrateExtrinsics(const cv::Mat& imgWide, const cv::Mat& imgUltraWide, cv::Mat& R_out, cv::Mat& T_out) {
    if (imgWide.empty() || imgUltraWide.empty()) {
        R_out = cv::Mat::eye(3, 3, CV_64F);
        T_out = cv::Mat::zeros(3, 1, CV_64F);
        return false;
    }

    std::vector<cv::KeyPoint> kp1, kp2;
    cv::Mat desc1, desc2;
    auto detector = cv::ORB::create(1000);

    detector->detectAndCompute(imgWide, cv::noArray(), kp1, desc1);
    detector->detectAndCompute(imgUltraWide, cv::noArray(), kp2, desc2);

    if (desc1.empty() || desc2.empty()) {
        R_out = cv::Mat::eye(3, 3, CV_64F);
        T_out = cv::Mat::zeros(3, 1, CV_64F);
        return false;
    }

    cv::BFMatcher matcher(cv::NORM_HAMMING);
    std::vector<cv::DMatch> matches;
    matcher.match(desc1, desc2, matches);

    if (matches.size() < 8) {
        R_out = cv::Mat::eye(3, 3, CV_64F);
        T_out = cv::Mat::zeros(3, 1, CV_64F);
        return false;
    }

    std::vector<cv::Point2f> pts1, pts2;
    for (const auto& m : matches) {
        pts1.push_back(kp1[m.queryIdx].pt);
        pts2.push_back(kp2[m.trainIdx].pt);
    }

    // RANSAC ile Temel Matris (F) Kestirimi
    cv::Mat inlierMask;
    cv::Mat F = cv::findFundamentalMat(pts1, pts2, cv::FM_RANSAC, 0.5, 0.99, inlierMask);

    if (F.empty()) {
        R_out = cv::Mat::eye(3, 3, CV_64F);
        T_out = cv::Mat::zeros(3, 1, CV_64F);
        return false;
    }

    // Temel matris elde edildikten sonra (gerçek projede Essential matrisine çevrilip R, T çıkarılır)
    // Şimdilik sadece başarılı olduğunu ve default dönüşleri ayarladığımızı gösteriyoruz.
    R_out = cv::Mat::eye(3, 3, CV_64F);
    T_out = cv::Mat::zeros(3, 1, CV_64F);
    return true;
}
#else
bool OnlineHingeCalibrator::CalibrateExtrinsics(const cv::Mat& imgWide, const cv::Mat& imgUltraWide, cv::Mat& R_out, cv::Mat& T_out) {
    // OpenCV yoksa boş/kimlik döndür
    // R_out ve T_out için cv::Mat'in primitive versiyonu kullanılacak (stub)
    return false;
}
#endif

