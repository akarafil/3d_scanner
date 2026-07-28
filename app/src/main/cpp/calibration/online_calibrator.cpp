#include "online_calibrator.h"

#if HAVE_OPENCV
bool OnlineHingeCalibrator::CalibrateExtrinsics(const cv::Mat& imgWide, const cv::Mat& imgUltraWide, cv::Mat& R_out, cv::Mat& T_out) {
    if (imgWide.empty() || imgUltraWide.empty()) {
        return false;
    }

    std::vector<cv::KeyPoint> kp1, kp2;
    cv::Mat desc1, desc2;
    auto detector = cv::ORB::create(1000);

    detector->detectAndCompute(imgWide, cv::noArray(), kp1, desc1);
    detector->detectAndCompute(imgUltraWide, cv::noArray(), kp2, desc2);

    if (desc1.empty() || desc2.empty()) {
        return false;
    }

    cv::BFMatcher matcher(cv::NORM_HAMMING);
    std::vector<cv::DMatch> matches;
    matcher.match(desc1, desc2, matches);

    if (matches.size() < 8) {
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

    return !F.empty();
}
#else
bool OnlineHingeCalibrator::CalibrateExtrinsics(const cv::Mat& imgWide, const cv::Mat& imgUltraWide, cv::Mat& R_out, cv::Mat& T_out) {
    return false;
}
#endif

